package io.vertx.mqtt.impl;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.mqtt.MqttConnectPayload;
import io.netty.handler.codec.mqtt.MqttConnectReturnCode;
import io.netty.handler.codec.mqtt.MqttConnectVariableHeader;
import io.netty.handler.codec.mqtt.MqttDecoder;
import io.netty.handler.codec.mqtt.MqttEncoder;
import io.netty.handler.codec.mqtt.MqttFixedHeader;
import io.netty.handler.codec.mqtt.MqttMessageFactory;
import io.netty.handler.codec.mqtt.MqttMessageIdVariableHeader;
import io.netty.handler.codec.mqtt.MqttMessageType;
import io.netty.handler.codec.mqtt.MqttPublishVariableHeader;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.handler.codec.mqtt.MqttSubscribePayload;
import io.netty.handler.codec.mqtt.MqttTopicSubscription;
import io.netty.handler.codec.mqtt.MqttUnsubscribePayload;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.impl.ContextImpl;
import io.vertx.core.impl.VertxInternal;
import io.vertx.core.net.impl.ConnectionBase;
import io.vertx.core.net.impl.NetClientBase;
import io.vertx.core.net.impl.SSLHelper;
import io.vertx.core.net.impl.VertxHandler;
import io.vertx.core.spi.metrics.TCPMetrics;
import io.vertx.mqtt.MqttClient;
import io.vertx.mqtt.MqttClientOptions;
import io.vertx.mqtt.MqttConnAckMessage;
import io.vertx.mqtt.MqttConnectionException;
import io.vertx.mqtt.MqttSubAckMessage;
import io.vertx.mqtt.messages.MqttPublishMessage;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.netty.handler.codec.mqtt.MqttQoS.*;


/**
 * MQTT client implementation
 */
public class MqttClientImpl extends NetClientBase<MqttClientConnection> implements MqttClient {

  private static final int MAX_MESSAGE_ID = 65535;
  private static final String PROTOCOL_NAME = "MQTT";
  private static final int PROTOCOL_VERSION = 4;

  private MqttClientOptions options;
  private ConnectionBase connection;

  // handler to call when a publish is complete
  Handler<Integer> publishCompleteHandler;
  // handler to call when a unsubscribe request is completed
  Handler<Integer> unsubscribeCompleteHandler;
  // handler to call when a publish message comes in
  Handler<MqttPublishMessage> publishHandler;
  // handler to call when a subscribe request is completed
  Handler<MqttSubAckMessage> subscribeCompleteHandler;
  // handler to call when a connection request is completed
  Handler<AsyncResult<MqttConnAckMessage>> connectHandler;
  // handler to call when a pingresp is received
  Handler<Void> pingrespHandler;

  // counter for the message identifier
  private int messageIdCounter;

  /**
   * Constructor
   *
   * @param vertx Vert.x instance
   * @param options MQTT client options
   */
  public MqttClientImpl(Vertx vertx, MqttClientOptions options) {
    super((VertxInternal) vertx, options, true);
    this.options = options;
  }

  /**
   * See {@link MqttClient#connect()} for more details
   */
  @Override
  public MqttClient connect() {
    return connect(null);
  }

  /**
   * See {@link MqttClient#connect(Handler)} for more details
   */
  @Override
  public MqttClient connect(Handler<AsyncResult<MqttConnAckMessage>> connectHandler) {

    this.doConnect(options.getPort(), options.getHost(), options.getHost(), done -> {

      // the TCP connection fails
      if (done.failed()) {

        if (connectHandler != null) {
          connectHandler.handle(Future.failedFuture(done.cause()));
        }
      } else {

        this.connection = done.result();
        this.connectHandler = connectHandler;

        MqttFixedHeader fixedHeader = new MqttFixedHeader(MqttMessageType.CONNECT,
          false,
          AT_MOST_ONCE,
          false,
          0);

        MqttConnectVariableHeader variableHeader = new MqttConnectVariableHeader(
          PROTOCOL_NAME,
          PROTOCOL_VERSION,
          options.hasUsername(),
          options.hasPassword(),
          options.isWillRetain(),
          options.getWillQoS(),
          options.isWillFlag(),
          options.isCleanSession(),
          options.getKeepAliveTimeSeconds()
        );

        MqttConnectPayload payload = new MqttConnectPayload(
          options.getClientId() == null ? "" : options.getClientId(),
          options.getWillTopic(),
          options.getWillMessage(),
          options.hasUsername() ? options.getUsername() : null,
          options.hasPassword() ? options.getPassword() : null
        );

        io.netty.handler.codec.mqtt.MqttMessage connect = MqttMessageFactory.newMessage(fixedHeader, variableHeader, payload);

        this.write(connect);
      }

    });
    return this;
  }

  /**
   * See {@link MqttClient#disconnect()} for more details
   */
  @Override
  public MqttClient disconnect() {
    return disconnect(null);
  }

  /**
   * See {@link MqttClient#disconnect(Handler)} for more details
   */
  @Override
  public MqttClient disconnect(Handler<AsyncResult<Void>> disconnectHandler) {

    MqttFixedHeader fixedHeader = new MqttFixedHeader(
      MqttMessageType.DISCONNECT,
      false,
      AT_MOST_ONCE,
      false,
      0
    );

    io.netty.handler.codec.mqtt.MqttMessage disconnect = MqttMessageFactory.newMessage(fixedHeader, null, null);

    this.write(disconnect);

    if (disconnectHandler != null) {
      disconnectHandler.handle(Future.succeededFuture());
    }

    this.connection.close();
    return this;
  }

  /**
   * See {@link MqttClient#publish(String, Buffer, MqttQoS, boolean, boolean)} for more details
   */
  @Override
  public MqttClient publish(String topic, Buffer payload, MqttQoS qosLevel, boolean isDup, boolean isRetain) {
    return publish(topic, payload, qosLevel, isDup, isRetain, null);
  }

  /**
   * See {@link MqttClient#publish(String, Buffer, MqttQoS, boolean, boolean, Handler)} for more details
   */
  @Override
  public MqttClient publish(String topic, Buffer payload, MqttQoS qosLevel, boolean isDup, boolean isRetain, Handler<AsyncResult<Integer>> publishSentHandler) {

    MqttFixedHeader fixedHeader = new MqttFixedHeader(
      MqttMessageType.PUBLISH,
      isDup,
      qosLevel,
      isRetain,
      0
    );

    MqttPublishVariableHeader variableHeader = new MqttPublishVariableHeader(topic, nextMessageId());

    ByteBuf buf = Unpooled.copiedBuffer(payload.getBytes());

    io.netty.handler.codec.mqtt.MqttMessage publish = MqttMessageFactory.newMessage(fixedHeader, variableHeader, buf);

    this.write(publish);

    if (publishSentHandler != null) {
      publishSentHandler.handle(Future.succeededFuture(variableHeader.messageId()));
    }

    return this;
  }

  /**
   * See {@link MqttClient#publishCompleteHandler(Handler)} for more details
   */
  @Override
  public MqttClient publishCompleteHandler(Handler<Integer> publishCompleteHandler) {

    this.publishCompleteHandler = publishCompleteHandler;
    return this;
  }

  /**
   * See {@link MqttClient#publishHandler(Handler)} for more details
   */
  @Override
  public MqttClient publishHandler(Handler<MqttPublishMessage> publishHandler) {

    this.publishHandler = publishHandler;
    return this;
  }

  /**
   * See {@link MqttClient#subscribeCompleteHandler(Handler)} for more details
   */
  @Override
  public MqttClient subscribeCompleteHandler(Handler<MqttSubAckMessage> subscribeCompleteHandler) {

    this.subscribeCompleteHandler = subscribeCompleteHandler;
    return this;
  }

  /**
   * See {@link MqttClient#subscribe(String, int)} for more details
   */
  @Override
  public MqttClient subscribe(String topic, int qos) {
    return subscribe(topic, qos, null);
  }

  /**
   * See {@link MqttClient#subscribe(String, int, Handler)} for more details
   */
  @Override
  public MqttClient subscribe(String topic, int qos, Handler<AsyncResult<Integer>> subscribeSentHandler) {
    return subscribe(Collections.singletonMap(topic, qos), subscribeSentHandler);
  }

  /**
   * See {@link MqttClient#subscribe(Map)} for more details
   */
  @Override
  public MqttClient subscribe(Map<String, Integer> topics) {
    return subscribe(topics, null);
  }

  /**
   * See {@link MqttClient#subscribe(Map, Handler)} for more details
   */
  @Override
  public MqttClient subscribe(Map<String, Integer> topics, Handler<AsyncResult<Integer>> subscribeSentHandler) {

    MqttFixedHeader fixedHeader = new MqttFixedHeader(
      MqttMessageType.SUBSCRIBE,
      false,
      AT_LEAST_ONCE,
      false,
      0);

    MqttMessageIdVariableHeader variableHeader = MqttMessageIdVariableHeader.from(nextMessageId());
    List<MqttTopicSubscription> subscriptions = topics.entrySet()
      .stream()
      .map(e -> new MqttTopicSubscription(e.getKey(), valueOf(e.getValue())))
      .collect(Collectors.toList());

    MqttSubscribePayload payload = new MqttSubscribePayload(subscriptions);

    io.netty.handler.codec.mqtt.MqttMessage subscribe = MqttMessageFactory.newMessage(fixedHeader, variableHeader, payload);

    this.write(subscribe);

    if (subscribeSentHandler != null) {
      subscribeSentHandler.handle(Future.succeededFuture(variableHeader.messageId()));
    }
    return this;
  }

  /**
   * See {@link MqttClient#unsubscribeCompleteHandler(Handler)} for more details
   */
  @Override
  public MqttClient unsubscribeCompleteHandler(Handler<Integer> unsubscribeCompleteHandler) {

    this.unsubscribeCompleteHandler = unsubscribeCompleteHandler;
    return this;
  }

  /**
   * See {@link MqttClient#unsubscribe(String, Handler)} )} for more details
   */
  @Override
  public MqttClient unsubscribe(String topic, Handler<AsyncResult<Integer>> unsubscribeSentHandler) {

    MqttFixedHeader fixedHeader = new MqttFixedHeader(
      MqttMessageType.UNSUBSCRIBE,
      false,
      AT_LEAST_ONCE,
      false,
      0);

    MqttMessageIdVariableHeader variableHeader = MqttMessageIdVariableHeader.from(nextMessageId());

    MqttUnsubscribePayload payload = new MqttUnsubscribePayload(Stream.of(topic).collect(Collectors.toList()));

    io.netty.handler.codec.mqtt.MqttMessage unsubscribe = MqttMessageFactory.newMessage(fixedHeader, variableHeader, payload);

    this.write(unsubscribe);

    if (unsubscribeSentHandler != null) {
      unsubscribeSentHandler.handle(Future.succeededFuture(variableHeader.messageId()));
    }
    return this;
  }

  /**
   * See {@link MqttClient#unsubscribe(String)} )} for more details
   */
  @Override
  public MqttClient unsubscribe(String topic) {
    return this.unsubscribe(topic, null);
  }

  /**
   * See {@link MqttClient#pingResponseHandler(Handler)} for more details
   */
  @Override
  public MqttClient pingResponseHandler(Handler<Void> pingResponseHandler) {

    this.pingrespHandler = pingResponseHandler;
    return this;
  }

  /**
   * See {@link MqttClient#ping()} for more details
   */
  @Override
  public MqttClient ping() {

    MqttFixedHeader fixedHeader =
      new MqttFixedHeader(MqttMessageType.PINGREQ, false, MqttQoS.AT_MOST_ONCE, false, 0);

    io.netty.handler.codec.mqtt.MqttMessage pingreq = MqttMessageFactory.newMessage(fixedHeader, null, null);

    this.write(pingreq);

    return this;
  }

  /**
   * Sends PUBACK packet to server
   *
   * @param publishMessageId identifier of the PUBLISH message to acknowledge
   */
  void publishAcknowledge(int publishMessageId) {

    MqttFixedHeader fixedHeader =
      new MqttFixedHeader(MqttMessageType.PUBACK, false, AT_MOST_ONCE, false, 0);

    MqttMessageIdVariableHeader variableHeader =
      MqttMessageIdVariableHeader.from(publishMessageId);

    io.netty.handler.codec.mqtt.MqttMessage puback = MqttMessageFactory.newMessage(fixedHeader, variableHeader, null);

    this.write(puback);
  }

  /**
   * Sends PUBREC packet to server
   *
   * @param publishMessageId identifier of the PUBLISH message to acknowledge
   */
  void publishReceived(int publishMessageId) {

    MqttFixedHeader fixedHeader =
      new MqttFixedHeader(MqttMessageType.PUBREC, false, AT_MOST_ONCE, false, 0);

    MqttMessageIdVariableHeader variableHeader =
      MqttMessageIdVariableHeader.from(publishMessageId);

    io.netty.handler.codec.mqtt.MqttMessage pubrec = MqttMessageFactory.newMessage(fixedHeader, variableHeader, null);

    this.write(pubrec);
  }

  /**
   * Sends PUBCOMP packet to server
   *
   * @param publishMessageId identifier of the PUBLISH message to acknowledge
   */
  void publishComplete(int publishMessageId) {

    MqttFixedHeader fixedHeader =
      new MqttFixedHeader(MqttMessageType.PUBCOMP, false, AT_MOST_ONCE, false, 0);

    MqttMessageIdVariableHeader variableHeader =
      MqttMessageIdVariableHeader.from(publishMessageId);

    io.netty.handler.codec.mqtt.MqttMessage pubcomp = MqttMessageFactory.newMessage(fixedHeader, variableHeader, null);

    this.write(pubcomp);
  }

  /**
   * Sends the PUBREL message to server
   *
   * @param publishMessageId  identifier of the PUBLISH message to acknowledge
   */
  void publishRelease(int publishMessageId) {

    MqttFixedHeader fixedHeader =
      new MqttFixedHeader(MqttMessageType.PUBREL, false, MqttQoS.AT_LEAST_ONCE, false, 0);

    MqttMessageIdVariableHeader variableHeader =
      MqttMessageIdVariableHeader.from(publishMessageId);

    io.netty.handler.codec.mqtt.MqttMessage pubrel = MqttMessageFactory.newMessage(fixedHeader, variableHeader, null);

    this.write(pubrel);
  }

  @Override
  protected MqttClientConnection createConnection(VertxInternal vertx, Channel channel, String host, int port,
                                                  ContextImpl context, SSLHelper helper, TCPMetrics metrics) {
    return new MqttClientConnection(vertx, channel, vertx.getOrCreateContext(), metrics, this.options, this);
  }

  @Override
  protected void handleMsgReceived(MqttClientConnection conn, Object msg) {
    connection = conn;
    conn.handleMessage(msg);
  }

  @Override
  protected void initChannel(ChannelPipeline pipeline) {
    // add into pipeline netty's (en/de)coder
    pipeline.addLast("mqttEncoder", MqttEncoder.INSTANCE);
    pipeline.addLast("mqttDecoder", new MqttDecoder());

    if (this.options.isAutoKeepAlive() &&
      this.options.getKeepAliveTimeSeconds() != 0) {

      pipeline.addLast("idle",
        new IdleStateHandler(0, this.options.getKeepAliveTimeSeconds(), 0));
      pipeline.addLast("keepAliveHandler", new ChannelDuplexHandler() {

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {

          if (evt instanceof IdleStateEvent) {
            IdleStateEvent e = (IdleStateEvent) evt;
            if (e.state() == IdleState.WRITER_IDLE) {
              ping();
            }
          }
        }

      });
    }
  }

  @Override
  protected Object safeObject(Object msg, ByteBufAllocator allocator) {

    // some Netty native MQTT messages need a mapping to Vert.x ones (available for polyglotization)
    // and different byte buffer resources are allocated
    if (msg instanceof io.netty.handler.codec.mqtt.MqttMessage) {

      io.netty.handler.codec.mqtt.MqttMessage mqttMessage = (io.netty.handler.codec.mqtt.MqttMessage) msg;
      DecoderResult result = mqttMessage.decoderResult();
      if (result.isSuccess() && result.isFinished()) {

        switch (mqttMessage.fixedHeader().messageType()) {

          case CONNACK:

            io.netty.handler.codec.mqtt.MqttConnAckMessage connack = (io.netty.handler.codec.mqtt.MqttConnAckMessage) mqttMessage;

            return MqttConnAckMessage.create(
              connack.variableHeader().connectReturnCode(),
              connack.variableHeader().isSessionPresent());

          case SUBACK:

            io.netty.handler.codec.mqtt.MqttSubAckMessage unsuback = (io.netty.handler.codec.mqtt.MqttSubAckMessage) mqttMessage;

            return MqttSubAckMessage.create(
              unsuback.variableHeader().messageId(),
              unsuback.payload().grantedQoSLevels());

          case PUBLISH:

            io.netty.handler.codec.mqtt.MqttPublishMessage publish = (io.netty.handler.codec.mqtt.MqttPublishMessage) mqttMessage;
            ByteBuf newBuf = VertxHandler.safeBuffer(publish.payload(), allocator);

            return MqttPublishMessage.create(
              publish.variableHeader().messageId(),
              publish.fixedHeader().qosLevel(),
              publish.fixedHeader().isDup(),
              publish.fixedHeader().isRetain(),
              publish.variableHeader().topicName(),
              newBuf);
        }
      }

    }

    // otherwise the original Netty message is returned
    return msg;
  }

  /**
   * Update and return the next message identifier
   *
   * @return message identifier
   */
  private int nextMessageId() {

    // if 0 or MAX_MESSAGE_ID, it becomes 1 (first valid messageId)
    this.messageIdCounter = ((this.messageIdCounter % MAX_MESSAGE_ID) != 0) ? this.messageIdCounter + 1 : 1;
    return this.messageIdCounter;
  }

  public MqttClientImpl write(io.netty.handler.codec.mqtt.MqttMessage mqttMessage) {

    synchronized (this.connection) {
      this.connection.writeToChannel(mqttMessage);
      return this;
    }
  }

  /**
   * Used for calling the pingresp handler when the server replies to the ping
   */
  void handlePingresp() {

    synchronized (this.connection) {
      if (this.pingrespHandler != null) {
        this.pingrespHandler.handle(null);
      }
    }
  }

  /**
   * Used for calling the unsuback handler when the server acks an unsubscribe
   *
   * @param unsubackMessageId identifier of the subscribe acknowledged by the server
   */
  void handleUnsuback(int unsubackMessageId) {

    synchronized (this.connection) {
      if (this.unsubscribeCompleteHandler != null) {
        this.unsubscribeCompleteHandler.handle(unsubackMessageId);
      }
    }
  }

  /**
   * Used for calling the puback handler when the server acknowledge a QoS 1 message with puback
   *
   * @param pubackMessageId identifier of the message acknowledged by the server
   */
  void handlePuback(int pubackMessageId) {

    synchronized (this.connection) {
      if (this.publishCompleteHandler != null) {
        this.publishCompleteHandler.handle(pubackMessageId);
      }
    }
  }

  /**
   * Used for calling the pubcomp handler when the server client acknowledge a QoS 2 message with pubcomp
   *
   * @param pubcompMessageId identifier of the message acknowledged by the server
   */
  void handlePubcomp(int pubcompMessageId) {

    synchronized (this.connection) {
      if (this.publishCompleteHandler != null) {
        this.publishCompleteHandler.handle(pubcompMessageId);
      }
    }
  }

  /**
   * Used for sending the pubrel when a pubrec is received from the server
   *
   * @param pubrecMessageId identifier of the message acknowledged by server
   */
  void handlePubrec(int pubrecMessageId) {

    synchronized (this.connection) {
      this.publishRelease(pubrecMessageId);
    }
  }

  /**
   * Used for calling the suback handler when the server acknoweldge subscribe to topics
   *
   * @param msg message with suback information
   */
  void handleSuback(MqttSubAckMessage msg) {

    synchronized (this.connection) {
      if (this.subscribeCompleteHandler != null) {
        this.subscribeCompleteHandler.handle(msg);
      }
    }
  }

  /**
   * Used for calling the publish handler when the server publishes a message
   *
   * @param msg published message
   */
  void handlePublish(MqttPublishMessage msg) {

    synchronized (this.connection) {

      switch (msg.qosLevel()) {

        case AT_MOST_ONCE:
          if (this.publishHandler != null) {
            this.publishHandler.handle(msg);
          }
          break;

        case AT_LEAST_ONCE:
          this.publishAcknowledge(msg.messageId());
          if (this.publishHandler != null) {
            this.publishHandler.handle(msg);
          }
          break;

        case EXACTLY_ONCE:
          this.publishReceived(msg.messageId());
          // TODO : save the PUBLISH message for raising the handler when PUBREL will arrive
          break;
      }

    }
  }

  /**
   * Used for calling the pubrel handler when the server acknowledge a QoS 2 message with pubrel
   *
   * @param pubrelMessageId identifier of the message acknowledged by the server
   */
  void handlePubrel(int pubrelMessageId) {

    synchronized (this.connection) {
      this.publishComplete(pubrelMessageId);

      // TODO : call publishHandler with the message received in the PUBISH
      //        we have to save it somewhere (maybe in a queue)
    }
  }

  /**
   * Used for calling the connect handler when the server replies to the request
   *
   * @param msg  connection response message
   */
  void handleConnack(MqttConnAckMessage msg) {

    synchronized (this.connection) {

      if (this.connectHandler != null) {

        if (msg.code() == MqttConnectReturnCode.CONNECTION_ACCEPTED) {
          this.connectHandler.handle(Future.succeededFuture(msg));
        } else {
          this.connectHandler.handle(Future.failedFuture(new MqttConnectionException(msg.code())));
        }
      }
    }
  }

}
