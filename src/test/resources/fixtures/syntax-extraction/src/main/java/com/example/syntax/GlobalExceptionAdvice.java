package com.example.syntax;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** advice 檔案整份跳過，包含同檔案內的其他型別 */
@RestControllerAdvice
public class GlobalExceptionAdvice {

    @GetMapping("/advice/handled")
    public String handled() {
        return "";
    }
}

/** 與 advice 同檔案的無關類別，依整檔跳過規則同樣不被掃描 */
class CoLocatedEndpoint {

    @GetMapping("/co-located")
    public String coLocated() {
        return "";
    }
}
