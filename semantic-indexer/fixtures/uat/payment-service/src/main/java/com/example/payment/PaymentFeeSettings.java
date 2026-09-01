package com.example.payment;

import java.util.Optional;

public interface PaymentFeeSettings {

    /** Loads deployment-specific fee formula JSON from runtime storage. */
    Optional<String> loadRuntimeFeeFormulaJson(PaymentMethod paymentMethod);
}
