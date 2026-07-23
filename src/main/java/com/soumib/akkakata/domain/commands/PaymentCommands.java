package com.soumib.akkakata.domain.commands;

public final class PaymentCommands {

    public record RecordPayment(String orderId, long amountCents) {}
}
