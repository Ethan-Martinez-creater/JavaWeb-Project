package com.eventflow.messaging.config;

/** Stable RabbitMQ topology names shared by publishers and listeners. */
public final class MessagingNames {
  public static final String EXCHANGE = "eventflow.events";

  public static final String ORDER_EXPIRY_DELAY_QUEUE = "eventflow.order-expiry.delay";
  public static final String ORDER_EXPIRY_QUEUE = "eventflow.order-expiry";
  public static final String ORDER_EXPIRY_RETRY_QUEUE = "eventflow.order-expiry.retry";

  public static final String NOTIFICATION_QUEUE = "eventflow.notifications";
  public static final String NOTIFICATION_RETRY_QUEUE = "eventflow.notifications.retry";

  public static final String DEAD_LETTER_QUEUE = "eventflow.dead-letter";

  public static final String ORDER_CREATED_ROUTE = "order.created";
  public static final String ORDER_EXPIRED_ROUTE = "order.expired";
  public static final String ORDER_PAID_ROUTE = "order.paid";
  public static final String ORDER_CLOSED_ROUTE = "order.closed";
  public static final String NOTIFICATION_RETRY_ROUTE = "notification.retry";
  public static final String ORDER_EXPIRY_RETRY_ROUTE = "order.expired.retry.delay";
  public static final String NOTIFICATION_RETRY_DELAY_ROUTE = "notification.retry.delay";
  public static final String DEAD_LETTER_ROUTE = "dead";

  public static final String RETRY_COUNT_HEADER = "x-eventflow-retry-count";
  public static final String ERROR_CODE_HEADER = "x-eventflow-error-code";

  private MessagingNames() {}
}
