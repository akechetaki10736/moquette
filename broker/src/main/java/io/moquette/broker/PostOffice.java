package io.moquette.broker;

import io.moquette.spi.impl.subscriptions.ISubscriptionsDirectory;
import io.moquette.spi.impl.subscriptions.Subscription;
import io.moquette.spi.impl.subscriptions.Topic;
import io.moquette.spi.security.IAuthorizatorPolicy;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.mqtt.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import static io.moquette.spi.impl.Utils.messageId;
import static io.netty.handler.codec.mqtt.MqttMessageIdVariableHeader.from;
import static io.netty.handler.codec.mqtt.MqttQoS.*;

class PostOffice {

    private static final class PublishedMessage {

        private final Topic topic;
        private final MqttQoS publishingQos;
        private final ByteBuf payload;

        PublishedMessage(Topic topic, MqttQoS publishingQos, ByteBuf payload) {
            this.topic = topic;
            this.publishingQos = publishingQos;
            this.payload = payload;
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(PostOffice.class);

    private final ConcurrentMap<String, Queue> queues = new ConcurrentHashMap<>();
    private final Authorizator authorizator;
    private final ISubscriptionsDirectory subscriptions;
    private final IRetainedRepository retainedRepository;
    private SessionRegistry sessionRegistry;

    PostOffice(ISubscriptionsDirectory subscriptions, IAuthorizatorPolicy authorizatorPolicy,
               IRetainedRepository retainedRepository) {
        this.authorizator = new Authorizator(authorizatorPolicy);
        this.subscriptions = subscriptions;
        this.retainedRepository = retainedRepository;
    }

    public void init(SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    void dropQueuesForClient(String clientId) {
        queues.remove(clientId);
    }

    public void fireWill(Session.Will will) {
        // MQTT 3.1.2.8-17
        publish2Subscribers(will.payload, new Topic(will.topic), will.qos);
    }

    public void sendQueuedMessagesWhileOffline(String clientId, Session targetSession) {
        LOG.trace("Republishing all saved messages for session {} on CId={}", targetSession, clientId);
        if (!queues.containsKey(clientId)) {
            return;
        }
        final Queue<PublishedMessage> queue = queues.get(clientId);
        for (PublishedMessage msgToPublish : queue) {
            sendPublishOnSessionAtQos(msgToPublish.topic, msgToPublish.publishingQos, targetSession, msgToPublish.payload);
        }
    }

    public void subscribeClientToTopics(MqttSubscribeMessage msg, String clientID, String username,
                                        MQTTConnection mqttConnection) {
        // verify which topics of the subscribe ongoing has read access permission
        int messageID = messageId(msg);
        List<MqttTopicSubscription> ackTopics = authorizator.verifyTopicsReadAccess(clientID, username, msg);
        MqttSubAckMessage ackMessage = doAckMessageFromValidateFilters(ackTopics, messageID);

        // store topics subscriptions in session
        List<Subscription> newSubscriptions = ackTopics.stream()
            .filter(req -> req.qualityOfService() != FAILURE)
            .map(req -> {
                final Topic topic = new Topic(req.topicName());
                return new Subscription(clientID, topic, req.qualityOfService());
            }).collect(Collectors.toList());

        for (Subscription subscription : newSubscriptions) {
            subscriptions.add(subscription);
        }

        // add the subscriptions to Session
        Session session = sessionRegistry.retrieve(clientID);
        session.addSubscriptions(newSubscriptions);

        // send ack message
        mqttConnection.sendSubAckMessage(messageID, ackMessage);

        publishRetainedMessagesForSubscriptions(clientID, newSubscriptions);

        // TODO notify the Observables
//        for (Subscription subscription : newSubscriptions) {
//            m_interceptor.notifyTopicSubscribed(newSubscription, username);
//        }
    }

    private void publishRetainedMessagesForSubscriptions(String clientID, List<Subscription> newSubscriptions) {
        Session targetSession = this.sessionRegistry.retrieve(clientID);
        for (Subscription subscription : newSubscriptions) {
            final String topicFilter = subscription.getTopicFilter().toString();
            final MqttPublishMessage retainedMsg = retainedRepository.retainedOnTopic(topicFilter);

            if (retainedMsg == null) {
                // not found
                continue;
            }

            final MqttQoS retainedQos = retainedMsg.fixedHeader().qosLevel();
            MqttQoS qos = lowerQosToTheSubscriptionDesired(subscription, retainedQos);

            final ByteBuf origPayload = retainedMsg.payload();
            ByteBuf payload = origPayload.retainedDuplicate();
            sendRetainedPublishOnSessionAtQos(subscription.getTopicFilter(), qos, targetSession, payload);
        }
    }

    /**
     * Create the SUBACK response from a list of topicFilters
     */
    private MqttSubAckMessage doAckMessageFromValidateFilters(List<MqttTopicSubscription> topicFilters, int messageId) {
        List<Integer> grantedQoSLevels = new ArrayList<>();
        for (MqttTopicSubscription req : topicFilters) {
            grantedQoSLevels.add(req.qualityOfService().value());
        }

        MqttFixedHeader fixedHeader = new MqttFixedHeader(MqttMessageType.SUBACK, false, AT_MOST_ONCE,
            false, 0);
        MqttSubAckPayload payload = new MqttSubAckPayload(grantedQoSLevels);
        return new MqttSubAckMessage(fixedHeader, from(messageId), payload);
    }

    public void unsubscribe(List<String> topics, MQTTConnection mqttConnection, int messageId) {
        final String clientID = mqttConnection.getClientId();
        for (String t : topics) {
            Topic topic = new Topic(t);
            boolean validTopic = topic.isValid();
            if (!validTopic) {
                // close the connection, not valid topicFilter is a protocol violation
                mqttConnection.dropConnection();
                LOG.warn("Topic filter is not valid. CId={}, topics: {}, offending topic filter: {}", clientID,
                    topics, topic);
                return;
            }

            LOG.trace("Removing subscription. CId={}, topic={}", clientID, topic);
            subscriptions.removeSubscription(topic, clientID);

            // TODO remove the subscriptions to Session
//            clientSession.unsubscribeFrom(topic);

            //TODO notify interceptors
//            String username = NettyUtils.userName(channel);
//            m_interceptor.notifyTopicUnsubscribed(topic.toString(), clientID, username);
        }

        // ack the client
        mqttConnection.sendUnsubAckMessage(topics, clientID, messageId);
    }

    void receivedPublishQos0(Topic topic, String username, String clientID, ByteBuf payload, boolean retain) {
        if (!authorizator.canWrite(topic, username, clientID)) {
            LOG.error("MQTT client is not authorized to publish on topic. CId={}, topic: {}", clientID, topic);
            return;
        }
        publish2Subscribers(payload, topic, AT_MOST_ONCE);

        if (retain) {
            // QoS == 0 && retain => clean old retained
            retainedRepository.cleanRetained(topic);
        }
// TODO
//        m_interceptor.notifyTopicPublished(msg, clientID, username);
    }

    void receivedPublishQos1(MQTTConnection connection, Topic topic, String username, ByteBuf payload, int messageID,
                             boolean retain, MqttPublishMessage msg) {
        // verify if topic can be write
        topic.getTokens();
        if (!topic.isValid()) {
            LOG.warn("Invalid topic format, force close the connection");
            connection.dropConnection();
            return;
        }
        final String clientId = connection.getClientId();
        if (!authorizator.canWrite(topic, username, clientId)) {
            LOG.error("MQTT client is not authorized to publish on topic. CId={}, topic: {}", clientId, topic);
            return;
        }

        publish2Subscribers(payload, topic, AT_LEAST_ONCE);

        connection.sendPubAck(messageID);

        if (retain) {
            if (!payload.isReadable()) {
                retainedRepository.cleanRetained(topic);
            } else {
                // before wasn't stored
                retainedRepository.retain(topic, msg);
            }
        }
//TODO
//        m_interceptor.notifyTopicPublished(msg, clientID, username);
    }

    private void publish2Subscribers(ByteBuf origPayload, Topic topic, MqttQoS publishingQos) {
        Set<Subscription> topicMatchingSubscriptions = subscriptions.matchQosSharpening(topic);

        for (final Subscription sub : topicMatchingSubscriptions) {
            MqttQoS qos = lowerQosToTheSubscriptionDesired(sub, publishingQos);
            Session targetSession = this.sessionRegistry.retrieve(sub.getClientId());

            boolean targetIsActive = targetSession != null && targetSession.connected();
            // TODO move all this logic into messageSender, which puts into the flightZone only the messages
            // that pull out of the queue.
            if (targetIsActive) {
                LOG.debug("Sending PUBLISH message to active subscriber CId: {}, topicFilter: {}, qos: {}",
                          sub.getClientId(), sub.getTopicFilter(), qos);
                // we need to retain because duplicate only copy r/w indexes and don't retain() causing
                // refCnt = 0
                ByteBuf payload = origPayload.retainedDuplicate();
                sendPublishOnSessionAtQos(topic, qos, targetSession, payload);
            } else {
                if (!targetSession.isClean()) {
                    LOG.debug("Storing pending PUBLISH inactive message. CId={}, topicFilter: {}, qos: {}",
                              sub.getClientId(), sub.getTopicFilter(), qos);
                    // store the message in targetSession queue to deliver
                    enqueueToClient(sub.getClientId(), new PublishedMessage(topic, publishingQos, origPayload));
                }
            }
        }
    }

    private void sendPublishOnSessionAtQos(Topic topic, MqttQoS qos, Session targetSession, ByteBuf payload) {
        if (qos != MqttQoS.AT_MOST_ONCE) {
            // QoS 1 or 2
            targetSession.sendPublishNotRetainedWithMessageId(topic, qos, payload);
        } else {
            targetSession.sendPublishNotRetained(topic, qos, payload);
        }
    }

    private void sendRetainedPublishOnSessionAtQos(Topic topic, MqttQoS qos, Session targetSession, ByteBuf payload) {
        if (qos != MqttQoS.AT_MOST_ONCE) {
            // QoS 1 or 2
            targetSession.sendRetainedPublishWithMessageId(topic, qos, payload);
        } else {
            targetSession.sendRetainedPublish(topic, qos, payload);
        }
    }

    private void enqueueToClient(String clientId, PublishedMessage msg) {
        queues.computeIfAbsent(clientId, (String cli) -> new ConcurrentLinkedQueue());
        queues.get(clientId).add(msg);
    }

    /**
     * Second phase of a publish QoS2 protocol, sent by publisher to the broker. Search the stored
     * message and publish to all interested subscribers.
     */
    void receivedPublishRelQos2(MQTTConnection connection, MqttPublishMessage mqttPublishMessage, int messageID) {
        LOG.trace("Processing PUBREL message on connection: {}", connection);
        final Topic topic = new Topic(mqttPublishMessage.variableHeader().topicName());
        final ByteBuf payload = mqttPublishMessage.payload();
        publish2Subscribers(payload, topic, EXACTLY_ONCE);

        connection.sendPubCompMessage(messageID);

        final boolean retained = mqttPublishMessage.fixedHeader().isRetain();
        if (retained) {
            if (!payload.isReadable()) {
                retainedRepository.cleanRetained(topic);
            } else {
                // before wasn't stored
                retainedRepository.retain(topic, mqttPublishMessage);
            }
        }

        //TODO here we should notify to the listeners
        //m_interceptor.notifyTopicPublished(msg, clientID, username);
    }

    static MqttQoS lowerQosToTheSubscriptionDesired(Subscription sub, MqttQoS qos) {
        if (qos.value() > sub.getRequestedQos().value()) {
            qos = sub.getRequestedQos();
        }
        return qos;
    }

    /**
     * Intended usage is only for embedded versions of the broker, where the hosting application
     * want to use the broker to send a publish message. Like normal external publish message but
     * with some changes to avoid security check, and the handshake phases for Qos1 and Qos2. It
     * also doesn't notifyTopicPublished because using internally the owner should already know
     * where it's publishing.
     *
     * @param msg
     *            the message to publish.
     * @param clientId
     *            the clientID
     */
    public void internalPublish(MqttPublishMessage msg, final String clientId) {
        final MqttQoS qos = msg.fixedHeader().qosLevel();
        final Topic topic = new Topic(msg.variableHeader().topicName());
        final ByteBuf payload = msg.payload();
        LOG.info("Sending internal PUBLISH message Topic={}, qos={}", topic, qos);

//        IMessagesStore.StoredMessage toStoreMsg = asStoredMessage(msg);
//        if (clientId == null || clientId.isEmpty()) {
//            toStoreMsg.setClientID("BROKER_SELF");
//        } else {
//            toStoreMsg.setClientID(clientId);
//        }
        publish2Subscribers(payload, topic, qos);

        if (!msg.fixedHeader().isRetain()) {
            return;
        }
        if (qos == AT_MOST_ONCE || msg.payload().readableBytes() == 0) {
            // QoS == 0 && retain => clean old retained
            retainedRepository.cleanRetained(topic);
            return;
        }
        retainedRepository.retain(topic, msg);
    }
}
