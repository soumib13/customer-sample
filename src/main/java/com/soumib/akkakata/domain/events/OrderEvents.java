package com.soumib.akkakata.domain.events;

import com.soumib.akkakata.domain.model.OrderState.OrderLine;
import java.util.List;

public sealed interface OrderEvents {

    record orderPlaced(
        String orderId,
        String customerId,
        List<OrderLine> lines,
        long totalCents
    ) implements OrderEvents {}

    record inventoryReserved(String orderId) implements OrderEvents {}
    record paymentRecorded(String orderId) implements OrderEvents {}
    record orderShipped(String orderId) implements OrderEvents {}
    record orderFailed(String orderId, String reason) implements OrderEvents {}
    record orderCancelled(String orderId) implements OrderEvents {}
}
