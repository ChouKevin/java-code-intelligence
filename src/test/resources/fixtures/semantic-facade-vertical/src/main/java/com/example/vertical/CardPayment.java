package com.example.vertical;

public final class CardPayment implements PaymentPort {

    @Override
    public void pay(String orderId) {
        String normalizedOrderId = orderId.trim();
    }

    @Override
    public String label() {
        return "card-payment";
    }
}
