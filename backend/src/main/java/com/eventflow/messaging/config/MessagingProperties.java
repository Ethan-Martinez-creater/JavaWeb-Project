package com.eventflow.messaging.config;

/** Runtime controls for durable publication and bounded consumer retries. */
public class MessagingProperties {
  private boolean enabled = true;
  private boolean publisherScheduleEnabled = true;
  private long publisherDelayMs = 1_000;
  private int publisherBatchSize = 50;
  private long claimLeaseMs = 30_000;
  private long confirmTimeoutMs = 5_000;
  private long publishBackoffBaseMs = 1_000;
  private long publishBackoffMaxMs = 300_000;
  private boolean expiryListenerEnabled = true;
  private boolean notificationListenerEnabled = true;
  private int consumerMaxRetries = 3;
  private long consumerRetryDelayMs = 5_000;

  public void validate() {
    if (publisherDelayMs < 1 || publisherBatchSize < 1 || publisherBatchSize > 100) {
      throw new IllegalStateException("Invalid messaging publisher schedule settings");
    }
    if (confirmTimeoutMs < 1
        || claimLeaseMs <= confirmTimeoutMs
        || claimLeaseMs - confirmTimeoutMs <= 2_000) {
      throw new IllegalStateException(
          "Outbox claim lease must exceed confirm timeout by 2 seconds");
    }
    if (publishBackoffBaseMs < 1 || publishBackoffMaxMs < publishBackoffBaseMs) {
      throw new IllegalStateException("Invalid outbox retry backoff settings");
    }
    if (consumerMaxRetries < 0
        || consumerMaxRetries > 20
        || consumerRetryDelayMs < 1
        || consumerRetryDelayMs > Integer.MAX_VALUE) {
      throw new IllegalStateException("Invalid messaging consumer retry settings");
    }
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public boolean isPublisherScheduleEnabled() {
    return publisherScheduleEnabled;
  }

  public void setPublisherScheduleEnabled(boolean publisherScheduleEnabled) {
    this.publisherScheduleEnabled = publisherScheduleEnabled;
  }

  public long getPublisherDelayMs() {
    return publisherDelayMs;
  }

  public void setPublisherDelayMs(long publisherDelayMs) {
    this.publisherDelayMs = publisherDelayMs;
  }

  public int getPublisherBatchSize() {
    return publisherBatchSize;
  }

  public void setPublisherBatchSize(int publisherBatchSize) {
    this.publisherBatchSize = publisherBatchSize;
  }

  public long getClaimLeaseMs() {
    return claimLeaseMs;
  }

  public void setClaimLeaseMs(long claimLeaseMs) {
    this.claimLeaseMs = claimLeaseMs;
  }

  public long getConfirmTimeoutMs() {
    return confirmTimeoutMs;
  }

  public void setConfirmTimeoutMs(long confirmTimeoutMs) {
    this.confirmTimeoutMs = confirmTimeoutMs;
  }

  public long getPublishBackoffBaseMs() {
    return publishBackoffBaseMs;
  }

  public void setPublishBackoffBaseMs(long publishBackoffBaseMs) {
    this.publishBackoffBaseMs = publishBackoffBaseMs;
  }

  public long getPublishBackoffMaxMs() {
    return publishBackoffMaxMs;
  }

  public void setPublishBackoffMaxMs(long publishBackoffMaxMs) {
    this.publishBackoffMaxMs = publishBackoffMaxMs;
  }

  public boolean isExpiryListenerEnabled() {
    return expiryListenerEnabled;
  }

  public void setExpiryListenerEnabled(boolean expiryListenerEnabled) {
    this.expiryListenerEnabled = expiryListenerEnabled;
  }

  public boolean isNotificationListenerEnabled() {
    return notificationListenerEnabled;
  }

  public void setNotificationListenerEnabled(boolean notificationListenerEnabled) {
    this.notificationListenerEnabled = notificationListenerEnabled;
  }

  public int getConsumerMaxRetries() {
    return consumerMaxRetries;
  }

  public void setConsumerMaxRetries(int consumerMaxRetries) {
    this.consumerMaxRetries = consumerMaxRetries;
  }

  public long getConsumerRetryDelayMs() {
    return consumerRetryDelayMs;
  }

  public void setConsumerRetryDelayMs(long consumerRetryDelayMs) {
    this.consumerRetryDelayMs = consumerRetryDelayMs;
  }
}
