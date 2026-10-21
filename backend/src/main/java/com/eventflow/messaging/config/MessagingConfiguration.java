package com.eventflow.messaging.config;

import jakarta.annotation.PostConstruct;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties
@EnableScheduling
public class MessagingConfiguration {
  @Bean
  @ConfigurationProperties(prefix = "app.messaging")
  MessagingProperties messagingProperties() {
    return new MessagingProperties();
  }

  @Bean
  MessagingSettingsValidator messagingSettingsValidator(MessagingProperties properties) {
    return new MessagingSettingsValidator(properties);
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(
      name = "app.messaging.enabled",
      havingValue = "true",
      matchIfMissing = true)
  static class RabbitTopologyConfiguration {
    @Bean
    Declarables messagingTopology(MessagingProperties properties) {
      var exchange = ExchangeBuilder.topicExchange(MessagingNames.EXCHANGE).durable(true).build();
      Queue expiryDelay =
          QueueBuilder.durable(MessagingNames.ORDER_EXPIRY_DELAY_QUEUE)
              .withArgument("x-dead-letter-exchange", MessagingNames.EXCHANGE)
              .withArgument("x-dead-letter-routing-key", MessagingNames.ORDER_EXPIRED_ROUTE)
              .build();
      Queue expiry =
          QueueBuilder.durable(MessagingNames.ORDER_EXPIRY_QUEUE)
              .withArgument("x-dead-letter-exchange", MessagingNames.EXCHANGE)
              .withArgument("x-dead-letter-routing-key", MessagingNames.DEAD_LETTER_ROUTE)
              .build();
      Queue expiryRetry =
          QueueBuilder.durable(MessagingNames.ORDER_EXPIRY_RETRY_QUEUE)
              .withArgument("x-message-ttl", Math.toIntExact(properties.getConsumerRetryDelayMs()))
              .withArgument("x-dead-letter-exchange", MessagingNames.EXCHANGE)
              .withArgument("x-dead-letter-routing-key", MessagingNames.ORDER_EXPIRED_ROUTE)
              .build();
      Queue notifications =
          QueueBuilder.durable(MessagingNames.NOTIFICATION_QUEUE)
              .withArgument("x-dead-letter-exchange", MessagingNames.EXCHANGE)
              .withArgument("x-dead-letter-routing-key", MessagingNames.DEAD_LETTER_ROUTE)
              .build();
      Queue notificationRetry =
          QueueBuilder.durable(MessagingNames.NOTIFICATION_RETRY_QUEUE)
              .withArgument("x-message-ttl", Math.toIntExact(properties.getConsumerRetryDelayMs()))
              .withArgument("x-dead-letter-exchange", MessagingNames.EXCHANGE)
              .withArgument("x-dead-letter-routing-key", MessagingNames.NOTIFICATION_RETRY_ROUTE)
              .build();
      Queue deadLetter = QueueBuilder.durable(MessagingNames.DEAD_LETTER_QUEUE).build();

      Binding expiryDelayBinding =
          BindingBuilder.bind(expiryDelay).to(exchange).with(MessagingNames.ORDER_CREATED_ROUTE);
      Binding expiryBinding =
          BindingBuilder.bind(expiry).to(exchange).with(MessagingNames.ORDER_EXPIRED_ROUTE);
      Binding expiryRetryBinding =
          BindingBuilder.bind(expiryRetry)
              .to(exchange)
              .with(MessagingNames.ORDER_EXPIRY_RETRY_ROUTE);
      Binding notificationPaidBinding =
          BindingBuilder.bind(notifications).to(exchange).with(MessagingNames.ORDER_PAID_ROUTE);
      Binding notificationClosedBinding =
          BindingBuilder.bind(notifications).to(exchange).with(MessagingNames.ORDER_CLOSED_ROUTE);
      Binding notificationRetryBinding =
          BindingBuilder.bind(notifications)
              .to(exchange)
              .with(MessagingNames.NOTIFICATION_RETRY_ROUTE);
      Binding notificationRetryQueueBinding =
          BindingBuilder.bind(notificationRetry)
              .to(exchange)
              .with(MessagingNames.NOTIFICATION_RETRY_DELAY_ROUTE);
      Binding deadLetterBinding =
          BindingBuilder.bind(deadLetter).to(exchange).with(MessagingNames.DEAD_LETTER_ROUTE);
      return new Declarables(
          exchange,
          expiryDelay,
          expiry,
          expiryRetry,
          notifications,
          notificationRetry,
          deadLetter,
          expiryDelayBinding,
          expiryBinding,
          expiryRetryBinding,
          notificationPaidBinding,
          notificationClosedBinding,
          notificationRetryBinding,
          notificationRetryQueueBinding,
          deadLetterBinding);
    }
  }

  static class MessagingSettingsValidator {
    private final MessagingProperties properties;

    MessagingSettingsValidator(MessagingProperties properties) {
      this.properties = properties;
    }

    @PostConstruct
    void validate() {
      properties.validate();
    }
  }
}
