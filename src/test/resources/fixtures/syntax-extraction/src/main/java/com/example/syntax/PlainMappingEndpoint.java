package com.example.syntax;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** 沒有 @RestController 的純 @RequestMapping 類別，必須仍被視為 API 入口 */
@RequestMapping("/plain")
public class PlainMappingEndpoint {

    @GetMapping("/ping")
    public String ping() {
        return "";
    }
}
