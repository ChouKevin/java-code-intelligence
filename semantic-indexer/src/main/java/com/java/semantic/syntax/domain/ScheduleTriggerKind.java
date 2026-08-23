package com.java.semantic.syntax.domain;

/**
 * 排程觸發方式
 * <p>
 * 舊分析器只有一個 cronExpression 欄位，把 fixedDelayString 的 "5000" 塞進去，
 * 讀的人無從分辨那是 cron 還是毫秒數
 */
public enum ScheduleTriggerKind {

    /** cron 運算式 */
    CRON,

    /** 前次結束到下次開始的固定間隔，值為毫秒 */
    FIXED_DELAY,

    /** 固定頻率，值為毫秒 */
    FIXED_RATE,

    /** XXL-Job handler 名稱，實際排程設定在調度中心 */
    JOB_HANDLER,

    /** 有排程註解但讀不出任何觸發設定 */
    UNSPECIFIED
}
