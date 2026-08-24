package com.example.payment;

import org.springframework.beans.factory.annotation.Value;

public final class PaymentEndpointSettings {
    @Value("${payment.gateway.base-url}")
    private String gatewayBaseUrl;
}
