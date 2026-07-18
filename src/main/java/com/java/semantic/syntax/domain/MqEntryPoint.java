package com.java.semantic.syntax.domain;

import java.util.List;

/**
 * 訊息佇列消費者
 *
 * @param name         方法名稱
 * @param description  方法 Javadoc
 * @param broker       訊息中介，決定該讀哪個屬性取得目的地
 * @param destinations 佇列或 topic，可能有多個
 */
public record MqEntryPoint(
        String name,
        String description,
        MqBroker broker,
        List<String> destinations) implements EntryPointMethod {

    public MqEntryPoint {
        destinations = List.copyOf(destinations);
    }

    @Override
    public EntryPointType type() {
        return EntryPointType.MQ;
    }
}
