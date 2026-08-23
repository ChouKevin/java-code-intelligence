package com.java.semantic.syntax.domain;

/** 入口類型 */
public enum EntryPointType {

    /** HTTP 端點 */
    API,

    /** 訊息佇列消費者 */
    MQ,

    /** 排程任務 */
    SCHEDULE
}
