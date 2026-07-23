package com.soumib.akkakata.domain.model;

public record PaymentRecord(
    String orderId,
    long amountCents,
    boolean charged
) {
    public static PaymentRecord empty() {
        return new PaymentRecord(null, 0L, false);
    }
}
