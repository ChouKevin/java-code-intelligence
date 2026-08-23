package com.example.vertical;

public interface PaymentPort {

    void pay(String orderId);

    default String label() {
        return "default-payment";
    }
}
