package com.example.vertical;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class OrderController {

    private final OrderService orderService = new OrderService(new CardPayment());

    @PostMapping("/orders")
    public String place(PlaceOrderRequest request) {
        return orderService.placeFromRest(request);
    }
}
