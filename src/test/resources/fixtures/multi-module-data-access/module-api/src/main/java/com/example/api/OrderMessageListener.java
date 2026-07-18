package com.example.api;

import com.example.service.OrderApplicationService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class OrderMessageListener {
    private final OrderApplicationService orderApplicationService = null;

    @RabbitListener(queues = "orders")
    public void consume(String orderNo) {
        orderApplicationService.loadOrder(orderNo);
    }
}
