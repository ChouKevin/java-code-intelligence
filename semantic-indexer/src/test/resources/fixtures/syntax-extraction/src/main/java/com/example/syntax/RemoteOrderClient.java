package com.example.syntax;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/** Feign client 是出站呼叫，不是入站端點，必須被排除 */
@FeignClient(name = "remote-order")
public interface RemoteOrderClient {

    @GetMapping("/remote/orders")
    String fetch();
}
