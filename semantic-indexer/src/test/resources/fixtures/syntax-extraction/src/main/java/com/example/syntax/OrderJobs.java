package com.example.syntax;

import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 排程任務集合 */
@Component
public class OrderJobs {

    /** cron 與 fixedDelayString 同時出現，且 fixedDelayString 寫在前面 */
    @Scheduled(fixedDelayString = "5000", cron = "0 0 1 * * *")
    public void bothTriggers() {
    }

    /** 純數值 fixedDelay，舊實作只讀 *String 變體 */
    @Scheduled(fixedDelay = 5000)
    public void numericFixedDelay() {
    }

    /** 純數值 fixedRate */
    @Scheduled(fixedRate = 30000)
    public void numericFixedRate() {
    }

    /** cron 來自跨檔案常量 */
    @Scheduled(cron = RouteConstants.CRON)
    public void constantCron() {
    }

    /** XXL-Job，舊實作完全沒有掃描 */
    @XxlJob("settleHandler")
    public void settle() {
    }
}
