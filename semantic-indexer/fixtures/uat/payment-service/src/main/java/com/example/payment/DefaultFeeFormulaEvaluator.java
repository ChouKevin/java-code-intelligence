package com.example.payment;

import java.math.BigDecimal;

public final class DefaultFeeFormulaEvaluator implements FeeFormulaEvaluator {

    @Override
    public BigDecimal evaluate(String formulaJson, BigDecimal amount) {
        return amount;
    }
}
