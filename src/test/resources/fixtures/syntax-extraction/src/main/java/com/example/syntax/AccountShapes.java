package com.example.syntax;

import lombok.experimental.Accessors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;

/** 型別形狀樣本：profiles、fluent accessors、record、enum */
@Service
@Profile({"dev", "uat"})
@Accessors(fluent = true)
public class AccountShapes {

    private String name;

    private int balance;

    public String name() {
        return name;
    }
}

/** record 不應成為入口候選，但必須出現在 class metadata 中 */
record AccountSummary(String accountNo, long total) {

    /** 帶映射註解也不該產出入口，record 不是 controller 宿主 */
    @GetMapping("/summary")
    String summary() {
        return accountNo;
    }
}

/** enum 同上 */
enum AccountStatus {
    ACTIVE, CLOSED;

    /** 帶映射註解也不該產出入口 */
    @GetMapping("/status")
    String label() {
        return name();
    }
}
