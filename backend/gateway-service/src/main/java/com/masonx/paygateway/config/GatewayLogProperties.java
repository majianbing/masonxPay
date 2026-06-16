package com.masonx.paygateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.gateway-logs")
public class GatewayLogProperties {

    public enum Mode {
        INLINE,
        KAFKA,
        DISABLED
    }

    private boolean enabled = true;
    private Mode mode = Mode.INLINE;
    private double sampleRate = 1.0;
    private String topic = "gateway.api.logs";
    private String groupId = "masonxpay-gateway-log-writer";
    private int batchSize = 250;
    private int consumerConcurrency = 1;
    private boolean consumerStoreEnabled = true;
    private int topicPartitions = 12;

    public boolean isEnabled() {
        return enabled && mode != Mode.DISABLED;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            this.mode = Mode.DISABLED;
        }
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode != null ? mode : Mode.INLINE;
    }

    public double getSampleRate() {
        return sampleRate;
    }

    public void setSampleRate(double sampleRate) {
        if (Double.isNaN(sampleRate)) {
            this.sampleRate = 0.0;
            return;
        }
        this.sampleRate = Math.max(0.0, Math.min(1.0, sampleRate));
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        if (topic != null && !topic.isBlank()) {
            this.topic = topic;
        }
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        if (groupId != null && !groupId.isBlank()) {
            this.groupId = groupId;
        }
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = Math.max(1, batchSize);
    }

    public int getConsumerConcurrency() {
        return consumerConcurrency;
    }

    public void setConsumerConcurrency(int consumerConcurrency) {
        this.consumerConcurrency = Math.max(1, consumerConcurrency);
    }

    public boolean isConsumerStoreEnabled() {
        return consumerStoreEnabled;
    }

    public void setConsumerStoreEnabled(boolean consumerStoreEnabled) {
        this.consumerStoreEnabled = consumerStoreEnabled;
    }

    public int getTopicPartitions() {
        return topicPartitions;
    }

    public void setTopicPartitions(int topicPartitions) {
        this.topicPartitions = Math.max(1, topicPartitions);
    }
}
