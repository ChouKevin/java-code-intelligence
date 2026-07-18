package com.example.syntax;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** MQ 消費者集合 */
@Component
public class OrderListeners {

    /** 多佇列，且 value 與 queues 同時出現 */
    @RabbitListener(value = "from-value", queues = {"q1", "q2"})
    public void rabbitMulti(String payload) {
    }

    /** 佇列名稱來自跨檔案常量 */
    @RabbitListener(queues = RouteConstants.QUEUE)
    public void rabbitConstant(String payload) {
    }

    /** Kafka 用的是 topics，舊實作完全沒有掃描 */
    @KafkaListener(topics = {"orders", "refunds"}, groupId = "g1")
    public void kafka(String payload) {
    }

    /** 巢狀類別的 MQ 方法，舊實作會重複計算 */
    @Component
    public static class InnerListener {

        @RabbitListener(queues = "inner-queue")
        public void inner(String payload) {
        }
    }
}
