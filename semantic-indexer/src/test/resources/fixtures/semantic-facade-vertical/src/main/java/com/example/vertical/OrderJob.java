package com.example.vertical;

import org.springframework.scheduling.annotation.Scheduled;

public final class OrderJob {

    private final OrderService orderService = new OrderService(new CardPayment());

    @Scheduled(cron = "0 * * * * *")
    public void cleanup() {
        orderService.cleanup();
    }
}
