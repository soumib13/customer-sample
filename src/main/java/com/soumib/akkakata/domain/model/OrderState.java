package com.soumib.akkakata.domain.model;

import java.util.List;

public record OrderState(
    String orderId,
    String customerId,
    List<OrderLine> lines,
    long totalCents,
    OrderStatus status,
    String failureReason
) {
    public static OrderState empty() {
        return new OrderState(null, null, List.of(), 0L, null, null);
    }

    public OrderState withStatus(OrderStatus next) {
        return new OrderState(orderId, customerId, lines, totalCents, next, failureReason);
    }

    public OrderState withFailure(String reason) {
        return new OrderState(orderId, customerId, lines, totalCents, OrderStatus.FAILED, reason);
    }

    public record OrderLine(String sku, int qty, long unitPriceCents) {}
}
