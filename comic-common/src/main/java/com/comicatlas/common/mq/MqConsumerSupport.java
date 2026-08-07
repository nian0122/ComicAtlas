package com.comicatlas.common.mq;

import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * MQ 消费编排支持：统一 ACK/Reject/中断语义（阿里规范）。
 * 组合优于继承——handler 注入本组件，handle 方法只保留业务逻辑与编排。
 */
@Component
public class MqConsumerSupport {

    private static final Logger LOG = LoggerFactory.getLogger(MqConsumerSupport.class);

    /** 消费失败策略（业务异常时）。 */
    public enum FailurePolicy {
        /** 默认：reject(requeue=false) → 进 DLQ，任务类消费失败 */
        REJECT_TO_DLQ,
        /** reject(requeue=true) → 原队列重试，取消类消息不能丢 */
        REQUEUE,
        /** 失败回调后 ack：失败事件即业务结果，不重试不进 DLQ */
        ACK_AFTER_CALLBACK
    }

    @FunctionalInterface
    public interface ConsumeAction {
        void run() throws Exception;
    }

    /** 失败回调：接收业务异常，用于发失败事件/更新状态（异常消息即失败事件内容）。 */
    @FunctionalInterface
    public interface ExceptionHandler {
        void accept(Exception e) throws Exception;
    }

    public void consume(Channel channel, long tag, String label, ConsumeAction action) {
        consume(channel, tag, label, action, null, FailurePolicy.REJECT_TO_DLQ);
    }

    public void consume(Channel channel, long tag, String label, ConsumeAction action, ExceptionHandler onFailure) {
        consume(channel, tag, label, action, onFailure, FailurePolicy.REJECT_TO_DLQ);
    }

    public void consume(Channel channel, long tag, String label, ConsumeAction action,
                        ExceptionHandler onFailure, FailurePolicy failurePolicy) {
        try {
            action.run();
            channel.basicAck(tag, false);
            LOG.info("MQ 消费完成: {}", label);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("MQ 消费被中断，结束任务: {}", label);
        } catch (Exception e) {
            LOG.error("MQ 消费失败: {}", label, e);
            runOnFailure(onFailure, e, label);
            try {
                if (failurePolicy == FailurePolicy.ACK_AFTER_CALLBACK) {
                    channel.basicAck(tag, false);
                } else {
                    channel.basicReject(tag, failurePolicy == FailurePolicy.REQUEUE);
                }
            } catch (Exception ex) {
                LOG.warn("消息 ack/reject 失败: tag={}, label={}", tag, label, ex);
            }
        }
    }

    private void runOnFailure(ExceptionHandler onFailure, Exception failure, String label) {
        if (onFailure == null) { return; }
        try {
            onFailure.accept(failure);
        } catch (Exception e) {
            LOG.error("MQ 失败回调执行异常: {}", label, e);
        }
    }
}
