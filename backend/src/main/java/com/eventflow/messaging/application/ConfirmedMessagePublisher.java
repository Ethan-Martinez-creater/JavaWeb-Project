package com.eventflow.messaging.application;

import com.eventflow.messaging.config.MessagingNames;
import com.eventflow.messaging.config.MessagingProperties;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/** Publishes persistent messages and waits for both routing and broker confirmation. */
@Component
public class ConfirmedMessagePublisher {
  private final RabbitTemplate rabbitTemplate;
  private final MessagingProperties properties;

  public ConfirmedMessagePublisher(RabbitTemplate rabbitTemplate, MessagingProperties properties) {
    this.rabbitTemplate = rabbitTemplate;
    this.properties = properties;
  }

  public void publish(
      String routingKey, String body, Map<String, Object> headers, Long expirationMs) {
    var correlation = new CorrelationData(UUID.randomUUID().toString());
    rabbitTemplate.convertAndSend(
        MessagingNames.EXCHANGE,
        routingKey,
        body,
        message -> {
          MessageProperties messageProperties = message.getMessageProperties();
          messageProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
          messageProperties.setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN);
          headers.forEach(messageProperties::setHeader);
          if (expirationMs != null) {
            messageProperties.setExpiration(Long.toString(Math.max(1, expirationMs)));
          }
          return message;
        },
        correlation);

    try {
      var confirmation =
          correlation.getFuture().get(properties.getConfirmTimeoutMs(), TimeUnit.MILLISECONDS);
      if (correlation.getReturned() != null) {
        throw new ConfirmedPublishException("UNROUTABLE");
      }
      if (!confirmation.isAck()) {
        throw new ConfirmedPublishException("PUBLISH_NACK");
      }
    } catch (TimeoutException exception) {
      throw new ConfirmedPublishException("CONFIRM_TIMEOUT");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new ConfirmedPublishException("CONFIRM_INTERRUPTED");
    } catch (ExecutionException exception) {
      throw new ConfirmedPublishException("CONFIRM_FAILED");
    }
  }

  public static class ConfirmedPublishException extends RuntimeException {
    private final String reasonCode;

    public ConfirmedPublishException(String reasonCode) {
      super(reasonCode);
      this.reasonCode = reasonCode;
    }

    public String reasonCode() {
      return reasonCode;
    }
  }
}
