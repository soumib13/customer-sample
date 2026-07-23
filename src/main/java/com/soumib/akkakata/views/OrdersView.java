package com.soumib.akkakata.views;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.soumib.akkakata.domain.entities.OrderEntity;
import com.soumib.akkakata.domain.events.OrderEvents.inventoryReserved;
import com.soumib.akkakata.domain.events.OrderEvents.orderCancelled;
import com.soumib.akkakata.domain.events.OrderEvents.orderFailed;
import com.soumib.akkakata.domain.events.OrderEvents.orderPlaced;
import com.soumib.akkakata.domain.events.OrderEvents.orderShipped;
import com.soumib.akkakata.domain.events.OrderEvents.paymentRecorded;
import com.soumib.akkakata.domain.model.OrderStatus;

import akka.javasdk.annotations.ComponentId;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.annotations.Table;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;

@ComponentId("orders-view")
public class OrdersView extends View {

    // reason is "" rather than Optional/null: the local view store's array codec
    // can't encode an Optional nested inside a List element (only at row top-level).
    public record HistoryEntry(String status, String reason) {}

    public record OrderRow(
        String orderId,
        String customerId,
        long totalCents,
        String status,
        Optional<String> failureReason,
        List<HistoryEntry> history
    ) {
        public OrderRow withStatus(String next) {
            return new OrderRow(orderId, customerId, totalCents, next, failureReason, appended(next, ""));
        }

        public OrderRow withFailure(String reason) {
            return new OrderRow(orderId, customerId, totalCents, OrderStatus.FAILED.name(), Optional.of(reason),
                appended(OrderStatus.FAILED.name(), reason));
        }

        private List<HistoryEntry> appended(String next, String reason) {
            var updated = new ArrayList<>(history);
            updated.add(new HistoryEntry(next, reason));
            return updated;
        }
    }

    public record OrderRows(List<OrderRow> orders) {}

    @Query("SELECT * AS orders FROM orders_table")
    public QueryEffect<OrderRows> all() {
        return queryResult();
    }

    @Query("SELECT * AS orders FROM orders_table WHERE customerId = :customerId")
    public QueryEffect<OrderRows> byCustomer(String customerId) {
        return queryResult();
    }

    @Query("SELECT * FROM orders_table WHERE orderId = :orderId")
    public QueryEffect<OrderRow> byId(String orderId) {
        return queryResult();
    }

    @Table("orders_table")
    @Consume.FromEventSourcedEntity(OrderEntity.class)
    public static class OrdersUpdater extends TableUpdater<OrderRow> {

        public Effect<OrderRow> onEvent(orderPlaced e) {
            return effects().updateRow(new OrderRow(
                e.orderId(), e.customerId(), e.totalCents(), OrderStatus.PLACED.name(), Optional.empty(),
                List.of(new HistoryEntry(OrderStatus.PLACED.name(), ""))));
        }

        public Effect<OrderRow> onEvent(inventoryReserved e) {
            return effects().updateRow(rowState().withStatus(OrderStatus.RESERVED.name()));
        }

        public Effect<OrderRow> onEvent(paymentRecorded e) {
            return effects().updateRow(rowState().withStatus(OrderStatus.PAID.name()));
        }

        public Effect<OrderRow> onEvent(orderShipped e) {
            return effects().updateRow(rowState().withStatus(OrderStatus.SHIPPED.name()));
        }

        public Effect<OrderRow> onEvent(orderFailed e) {
            return effects().updateRow(rowState().withFailure(e.reason()));
        }

        public Effect<OrderRow> onEvent(orderCancelled e) {
            return effects().updateRow(rowState().withStatus(OrderStatus.CANCELLED.name()));
        }
    }
}
