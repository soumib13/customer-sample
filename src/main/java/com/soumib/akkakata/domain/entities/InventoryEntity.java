package com.soumib.akkakata.domain.entities;

import akka.javasdk.annotations.ComponentId;
import akka.javasdk.keyvalueentity.KeyValueEntity;

import com.soumib.akkakata.domain.commands.InventoryCommands.ReleaseInventory;
import com.soumib.akkakata.domain.commands.InventoryCommands.ReserveInventory;
import com.soumib.akkakata.domain.commands.InventoryCommands.RestockInventory;
import com.soumib.akkakata.domain.model.InventoryState;

import java.util.HashMap;
import java.util.HashSet;

@ComponentId("inventory")
public class InventoryEntity extends KeyValueEntity<InventoryState> {

    public static final String ID = "main-warehouse";

    @Override
    public InventoryState emptyState() {
        return InventoryState.empty();
    }

    public Effect<String> reserve(ReserveInventory cmd) {
        if (currentState().reservedOrders().contains(cmd.orderId())) {
            return effects().reply("ALREADY_RESERVED");
        }
        var stock = new HashMap<>(currentState().stock());
        for (var line : cmd.lines()) {
            int on = stock.getOrDefault(line.sku(), 0);
            if (on < line.qty()) {
                return effects().reply("INSUFFICIENT:" + line.sku());
            }
            stock.put(line.sku(), on - line.qty());
        }
        var reserved = new HashSet<>(currentState().reservedOrders());
        reserved.add(cmd.orderId());
        return effects()
            .updateState(new InventoryState(stock, reserved))
            .thenReply("OK");
    }

    public Effect<String> release(ReleaseInventory cmd) {
        if (!currentState().reservedOrders().contains(cmd.orderId())) {
            return effects().reply("NOT_RESERVED");
        }
        var reserved = new HashSet<>(currentState().reservedOrders());
        reserved.remove(cmd.orderId());
        return effects()
            .updateState(new InventoryState(currentState().stock(), reserved))
            .thenReply("OK");
    }

    public Effect<String> restock(RestockInventory cmd) {
        if (cmd.qty() <= 0) {
            return effects().reply("INVALID_QTY");
        }
        var stock = new HashMap<>(currentState().stock());
        stock.merge(cmd.sku(), cmd.qty(), Integer::sum);
        return effects()
            .updateState(new InventoryState(stock, currentState().reservedOrders()))
            .thenReply("OK");
    }

    public ReadOnlyEffect<InventoryState> get() {
        return effects().reply(currentState());
    }
}
