package com.example.vertical;

public final class CashPayment implements PaymentPort {

    @Override
    public void pay(String orderId) {
        String normalizedOrderId = orderId.trim();
    }
}
