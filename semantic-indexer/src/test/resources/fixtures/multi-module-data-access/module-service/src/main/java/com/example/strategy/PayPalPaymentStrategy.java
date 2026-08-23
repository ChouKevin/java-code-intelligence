package com.example.strategy;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("paypal")
public class PayPalPaymentStrategy implements PaymentStrategy {

    @Override
    public void processPayment(String userId) {
    }
}
