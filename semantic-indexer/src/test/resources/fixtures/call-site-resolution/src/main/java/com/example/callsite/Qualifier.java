package com.example.callsite;

/** 本地最小 @Qualifier 樁；fixture 類路徑沒有 Spring，證據規則僅比對簡單名稱 */
public @interface Qualifier {

    String value();
}
