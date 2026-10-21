package com.eventflow.messaging.application;

import com.eventflow.messaging.application.ConfirmedMessagePublisher.ConfirmedPublishException;
import com.eventflow.messaging.application.ExpiryHandlingResult.Outcome;
import com.eventflow.messaging.config.MessagingNames;
import com.eventflow.messaging.domain.PoisonMessageException;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class OrderExpiryListener {
  private static final Logger log = LoggerFactory.getLogger(OrderExpiryListener.class);

  private final OrderExpiryMessageHandler handler;
  private final ReliableMessageDelivery delivery;
  private final Clock clock;

  public OrderExpiryListener(
      OrderExpiryMessageHandler handler, ReliableMessageDelivery delivery, Clock clock) {
    this.handler = handler;
    this.delivery = delivery;
    this.clock = clock;
  }

  @RabbitListener(
      id = "eventflow-order-expiry-v1",
      queues = MessagingNames.ORDER_EXPIRY_QUEUE,
      autoStartup = "${app.messaging.expiry-listener-enabled:true}")
  public void receive(Message message, Channel channel) throws IOException {
    long deliveryTag = message.getMessageProperties().getDeliveryTag();
    String eventId;
    try {
      eventId = delivery.eventId(message);
    } catch (PoisonMessageException exception) {
      delivery.quarantine(exception.reasonCode(), exception.reasonCode());
      channel.basicAck(deliveryTag, false);
      return;
    }

    try {
      ExpiryHandlingResult result = handler.handle(eventId);
      if (result.outcome() == Outcome.NOT_DUE) {
        long remaining = Duration.between(clock.instant(), result.expiresAt()).toMillis();
        delivery.postponeExpiry(eventId, remaining);
      }
      channel.basicAck(deliveryTag, false);
    } catch (PoisonMessageException exception) {
      delivery.quarantine(eventId, exception.reasonCode());
      channel.basicAck(deliveryTag, false);
    } catch (RuntimeException exception) {
      retry(eventId, message, channel, deliveryTag, exception);
    }
  }

  private void retry(
      String eventId, Message message, Channel channel, long deliveryTag, RuntimeException error)
      throws IOException {
    try {
      delivery.retryOrQuarantine(
          message,
          eventId,
          MessagingNames.ORDER_EXPIRED_ROUTE,
          error instanceof ConfirmedPublishException confirmed
              ? confirmed.reasonCode()
              : "EXPIRY_PROCESSING_ERROR");
      channel.basicAck(deliveryTag, false);
    } catch (RuntimeException publishError) {
      log.error(
          "Expiry message could not be forwarded eventId={} type={}",
          eventId,
          publishError.getClass().getName());
      channel.basicReject(deliveryTag, false);
    }
  }
}
