package com.java.semantic.semantic.adapter.jdtls;

/** 提供可注入的單調時間來源 */
@FunctionalInterface
public interface JdtMonotonicTicker {

    long readNanos();
}
