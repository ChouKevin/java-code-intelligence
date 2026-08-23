package com.example.syntax;

/** 跨檔案常量來源，用來驗證 binding 解析與常量摺疊 */
public final class RouteConstants {

    public static final String BASE = "/const";

    public static final String DETAIL = "/detail";

    /** 編譯期字串串接，JDT 應摺疊成 "/a/b" */
    public static final String FOLDED = "/a" + "/b";

    public static final String QUEUE = "const-queue";

    public static final String CRON = "0 0 * * * *";

    private RouteConstants() {
    }
}
