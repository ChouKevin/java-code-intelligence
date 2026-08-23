package com.example.strategy;

import com.example.repository.UserMapper;

public class PaymentService {

    private final PaymentStrategy paymentStrategy = new StripePaymentStrategy();
    private final UserMapper userMapper = new UserMapperImpl();

    public void checkout() {
        paymentStrategy.processPayment("u-1");
        userMapper.findById(1L);
    }
}
