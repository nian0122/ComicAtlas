package com.comicatlas.api.dlq.service;

import java.util.List;

/** DLQ 管理应用服务契约。 */
public interface DlqService {
    List<DlqQueueVO> listQueues();

    List<DlqBrokerClient.DlqMessage> getMessages(String queueName, int count);

    ReplayResult replay(String queueName, int maxMessages);

    PurgeResult purge(String queueName);

   @lombok.Getter

    class DlqRoute {
        private final String exchange;
        private final String routingKey;
        public DlqRoute(String exchange, String routingKey) {
            this.exchange = exchange;
            this.routingKey = routingKey;
        }
        public String exchange() { return exchange; }
        public String routingKey() { return routingKey; }
        @Override
        public boolean equals(Object other) {
            if (this == other) { return true; }
            if (!(other instanceof DlqRoute)) { return false; }
            DlqRoute that = (DlqRoute) other;
            return java.util.Objects.equals(exchange, that.exchange) && java.util.Objects.equals(routingKey, that.routingKey);
        }
        @Override
        public int hashCode() { return java.util.Objects.hash(exchange, routingKey); }
        @Override
        public String toString() { return "DlqRoute[" + "exchange=" + exchange + ", " + "routingKey=" + routingKey + "]"; }
    }

   @lombok.Getter

    class DlqQueueVO {
        private final String name;
        private final String exchange;
        private final String routingKey;
        private final String originalQueue;
        private final int messages;
        private final int consumers;
        public DlqQueueVO(String name, String exchange, String routingKey, String originalQueue, int messages, int consumers) {
            this.name = name;
            this.exchange = exchange;
            this.routingKey = routingKey;
            this.originalQueue = originalQueue;
            this.messages = messages;
            this.consumers = consumers;
        }
        public String name() { return name; }
        public String exchange() { return exchange; }
        public String routingKey() { return routingKey; }
        public String originalQueue() { return originalQueue; }
        public int messages() { return messages; }
        public int consumers() { return consumers; }
        @Override
        public boolean equals(Object other) {
            if (this == other) { return true; }
            if (!(other instanceof DlqQueueVO)) { return false; }
            DlqQueueVO that = (DlqQueueVO) other;
            return java.util.Objects.equals(name, that.name) && java.util.Objects.equals(exchange, that.exchange) && java.util.Objects.equals(routingKey, that.routingKey) && java.util.Objects.equals(originalQueue, that.originalQueue) && java.util.Objects.equals(messages, that.messages) && java.util.Objects.equals(consumers, that.consumers);
        }
        @Override
        public int hashCode() { return java.util.Objects.hash(name, exchange, routingKey, originalQueue, messages, consumers); }
        @Override
        public String toString() { return "DlqQueueVO[" + "name=" + name + ", " + "exchange=" + exchange + ", " + "routingKey=" + routingKey + ", " + "originalQueue=" + originalQueue + ", " + "messages=" + messages + ", " + "consumers=" + consumers + "]"; }
    }

   @lombok.Getter

    class ReplayResult {
        private final String queue;
        private final int attempted;
        private final int replayed;
        private final int remaining;
        private final boolean completed;
        private final String error;
        public ReplayResult(String queue, int attempted, int replayed, int remaining, boolean completed, String error) {
            this.queue = queue;
            this.attempted = attempted;
            this.replayed = replayed;
            this.remaining = remaining;
            this.completed = completed;
            this.error = error;
        }
        public String queue() { return queue; }
        public int attempted() { return attempted; }
        public int replayed() { return replayed; }
        public int remaining() { return remaining; }
        public boolean completed() { return completed; }
        public String error() { return error; }
        @Override
        public boolean equals(Object other) {
            if (this == other) { return true; }
            if (!(other instanceof ReplayResult)) { return false; }
            ReplayResult that = (ReplayResult) other;
            return java.util.Objects.equals(queue, that.queue) && java.util.Objects.equals(attempted, that.attempted) && java.util.Objects.equals(replayed, that.replayed) && java.util.Objects.equals(remaining, that.remaining) && java.util.Objects.equals(completed, that.completed) && java.util.Objects.equals(error, that.error);
        }
        @Override
        public int hashCode() { return java.util.Objects.hash(queue, attempted, replayed, remaining, completed, error); }
        @Override
        public String toString() { return "ReplayResult[" + "queue=" + queue + ", " + "attempted=" + attempted + ", " + "replayed=" + replayed + ", " + "remaining=" + remaining + ", " + "completed=" + completed + ", " + "error=" + error + "]"; }
    }

   @lombok.Getter

    class PurgeResult {
        private final String queue;
        private final int purged;
        public PurgeResult(String queue, int purged) {
            this.queue = queue;
            this.purged = purged;
        }
        public String queue() { return queue; }
        public int purged() { return purged; }
        @Override
        public boolean equals(Object other) {
            if (this == other) { return true; }
            if (!(other instanceof PurgeResult)) { return false; }
            PurgeResult that = (PurgeResult) other;
            return java.util.Objects.equals(queue, that.queue) && java.util.Objects.equals(purged, that.purged);
        }
        @Override
        public int hashCode() { return java.util.Objects.hash(queue, purged); }
        @Override
        public String toString() { return "PurgeResult[" + "queue=" + queue + ", " + "purged=" + purged + "]"; }
    }
}
