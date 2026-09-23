package com.comicatlas.api.dlq.service;

import java.util.List;
import java.util.Map;

public interface DlqBrokerClient {

    QueueStats queueStats(String queueName);

    List<DlqMessage> peek(String queueName, int count);

    ReplayBatch replay(
        String queueName,
        String exchange,
        String routingKey,
        int maxMessages
    );

    int purge(String queueName);

   @lombok.Getter

    class QueueStats {
        private final int messages;
        private final int consumers;
        public QueueStats(int messages, int consumers) {
            this.messages = messages;
            this.consumers = consumers;
        }
        public int messages() { return messages; }
        public int consumers() { return consumers; }
        @Override
        public boolean equals(Object other) {
            if (this == other) { return true; }
            if (!(other instanceof QueueStats)) { return false; }
            QueueStats that = (QueueStats) other;
            return java.util.Objects.equals(messages, that.messages) && java.util.Objects.equals(consumers, that.consumers);
        }
        @Override
        public int hashCode() { return java.util.Objects.hash(messages, consumers); }
        @Override
        public String toString() { return "QueueStats[" + "messages=" + messages + ", " + "consumers=" + consumers + "]"; }
    }

   @lombok.Getter

    class DlqMessage {
        private final String payload;
        private final String payloadEncoding;
        private final Map<String, Object> properties;
        private final int messagesRemaining;
        public String payload() { return payload; }
        public String payloadEncoding() { return payloadEncoding; }
        public Map<String, Object> properties() { return properties; }
        public int messagesRemaining() { return messagesRemaining; }
        @Override
        public boolean equals(Object other) {
            if (this == other) { return true; }
            if (!(other instanceof DlqMessage)) { return false; }
            DlqMessage that = (DlqMessage) other;
            return java.util.Objects.equals(payload, that.payload) && java.util.Objects.equals(payloadEncoding, that.payloadEncoding) && java.util.Objects.equals(properties, that.properties) && java.util.Objects.equals(messagesRemaining, that.messagesRemaining);
        }
        @Override
        public int hashCode() { return java.util.Objects.hash(payload, payloadEncoding, properties, messagesRemaining); }
        @Override
        public String toString() { return "DlqMessage[" + "payload=" + payload + ", " + "payloadEncoding=" + payloadEncoding + ", " + "properties=" + properties + ", " + "messagesRemaining=" + messagesRemaining + "]"; }
        public DlqMessage(String payload, String payloadEncoding, Map<String, Object> properties, int messagesRemaining) {
            this.payload = payload;
            this.payloadEncoding = payloadEncoding;
            this.properties = Map.copyOf(properties);
            this.messagesRemaining = messagesRemaining;
        }
    }

   @lombok.Getter

    class ReplayBatch {
        private final int attempted;
        private final int replayed;
        private final int remaining;
        private final boolean completed;
        private final String error;
        public ReplayBatch(int attempted, int replayed, int remaining, boolean completed, String error) {
            this.attempted = attempted;
            this.replayed = replayed;
            this.remaining = remaining;
            this.completed = completed;
            this.error = error;
        }
        public int attempted() { return attempted; }
        public int replayed() { return replayed; }
        public int remaining() { return remaining; }
        public boolean completed() { return completed; }
        public String error() { return error; }
        @Override
        public boolean equals(Object other) {
            if (this == other) { return true; }
            if (!(other instanceof ReplayBatch)) { return false; }
            ReplayBatch that = (ReplayBatch) other;
            return java.util.Objects.equals(attempted, that.attempted) && java.util.Objects.equals(replayed, that.replayed) && java.util.Objects.equals(remaining, that.remaining) && java.util.Objects.equals(completed, that.completed) && java.util.Objects.equals(error, that.error);
        }
        @Override
        public int hashCode() { return java.util.Objects.hash(attempted, replayed, remaining, completed, error); }
        @Override
        public String toString() { return "ReplayBatch[" + "attempted=" + attempted + ", " + "replayed=" + replayed + ", " + "remaining=" + remaining + ", " + "completed=" + completed + ", " + "error=" + error + "]"; }
    }
}
