package com.java.semantic.syntax.domain;

/**
 * 訊息中介
 * <p>
 * 範圍刻意等同 call graph 分類器既有的集合，不擴充到 JMS 或 RocketMQ
 */
public enum MqBroker {

    /** @RabbitListener，目的地寫在 queues */
    RABBIT,

    /** @KafkaListener，目的地寫在 topics */
    KAFKA
}
