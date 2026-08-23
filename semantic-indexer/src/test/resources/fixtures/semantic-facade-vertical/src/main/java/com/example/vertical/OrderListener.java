package com.example.vertical;

import org.springframework.amqp.rabbit.annotation.RabbitListener;

public final class OrderListener {

    private final OrderService orderService = new OrderService(new CardPayment());

    @RabbitListener(queues = "orders")
    public String consume(String orderId) {
        return orderService.placeFromMessage(orderId);
    }
}
