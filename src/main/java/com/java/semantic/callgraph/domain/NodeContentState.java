package com.java.semantic.callgraph.domain;

/** 節點 canonical 方法原始碼的內部可用性，圖形回應不內嵌原始碼 */
public enum NodeContentState {
    FULL_SOURCE,
    TARGET_ONLY,
    EXTERNAL
}
