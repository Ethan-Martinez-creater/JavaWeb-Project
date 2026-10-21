package com.eventflow.notification.application;

import com.eventflow.messaging.application.ConfirmedMessagePublisher.ConfirmedPublishException;
import com.eventflow.messaging.application.ReliableMessageDelivery;
import com.eventflow.messaging.config.MessagingNames;
import com.eventflow.messaging.domain.PoisonMessageException;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationListener {
  private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);

  private final NotificationMessageHandler handler;
  private final ReliableMessageDelivery delivery;

  public NotificationListener(
      NotificationMessageHandler handler, ReliableMessageDelivery delivery) {
    this.handler = handler;
    this.delivery = delivery;
  }

  @RabbitListener(
      id = "eventflow-notification-v1",
      queues = MessagingNames.NOTIFICATION_QUEUE,
      autoStartup = "${app.messaging.notification-listener-enabled:true}")
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
      handler.handle(eventId);
      channel.basicAck(deliveryTag, false);
    } catch (PoisonMessageException exception) {
      delivery.quarantine(eventId, exception.reasonCode());
      channel.basicAck(deliveryTag, false);
    } catch (RuntimeException exception) {
      try {
        delivery.retryOrQuarantine(
            message,
            eventId,
            MessagingNames.NOTIFICATION_RETRY_ROUTE,
            exception instanceof ConfirmedPublishException confirmed
                ? confirmed.reasonCode()
                : "NOTIFICATION_PROCESSING_ERROR");
        channel.basicAck(deliveryTag, false);
      } catch (RuntimeException publishError) {
        log.error(
            "Notification message could not be forwarded eventId={} type={}",
            eventId,
            publishError.getClass().getName());
        channel.basicReject(deliveryTag, false);
      }
    }
  }
}
