package com.example.strategy;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("stripe")
public class StripePaymentStrategy implements PaymentStrategy {

    @Override
    public void processPayment(String userId) {
    }
}
