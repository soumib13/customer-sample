package com.soumib.akkakata.domain.commands;

import com.soumib.akkakata.domain.model.OrderState.OrderLine;
import java.util.List;

public final class OrderCommands {

    public record PlaceOrder(
        String customerId,
        List<OrderLine> lines
    ) {}

    public record MarkReserved() {}
    public record MarkPaid() {}
    public record MarkShipped() {}
    public record MarkFailed(String reason) {}
    public record CancelIfNotPaid() {}
}
