package com.example.vertical;

public final class OrderService {

    private final PaymentPort paymentPort;

    public OrderService(PaymentPort paymentPort) {
        this.paymentPort = paymentPort;
    }

    public String placeFromRest(PlaceOrderRequest request) {
        return placeFromMessage(request.orderId());
    }

    public String placeFromMessage(String orderId) {
        paymentPort.pay(orderId);
        return paymentPort.label();
    }

    public void cleanup() {
        paymentPort.pay("scheduled-order");
    }
}
