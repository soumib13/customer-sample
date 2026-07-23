package com.soumib.akkakata.domain.commands;

import java.util.List;
import com.soumib.akkakata.domain.model.OrderState.OrderLine;

public final class InventoryCommands {

    public record ReserveInventory(String orderId, List<OrderLine> lines) {}
    public record ReleaseInventory(String orderId) {}
    public record RestockInventory(String sku, int qty) {}
}

