package com.soumib.akkakata.domain.model;

import java.util.Map;
import java.util.Set;

public record InventoryState(
    Map<String, Integer> stock,
    Set<String> reservedOrders
) {
    public static InventoryState empty() {
        return new InventoryState(
            Map.of("sku-1", 100, "sku-2", 100, "sku-3", 25),
            Set.of()
        );
    }
}
