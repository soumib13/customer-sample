package com.soumib.akkakata.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;

import com.soumib.akkakata.domain.commands.OrderCommands.CancelIfNotPaid;
import com.soumib.akkakata.domain.entities.OrderEntity;
import com.soumib.akkakata.domain.model.OrderState;
import com.soumib.akkakata.domain.model.OrderState.OrderLine;
import com.soumib.akkakata.domain.workflows.OrderWorkflow;
import com.soumib.akkakata.domain.workflows.OrderWorkflow.StartOrder;
import com.soumib.akkakata.views.OrdersView;
import com.soumib.akkakata.views.OrdersView.HistoryEntry;
import com.soumib.akkakata.views.OrdersView.OrderRows;

import java.util.List;
import java.util.UUID;

@HttpEndpoint("/api")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class OrderEndpoint {

    // idempotencyKey is optional: pass the same key on a retried request to avoid
    // creating a duplicate order (the workflow id is derived from it).
    public record PlaceOrderRequest(String customerId, List<OrderLine> lines, String idempotencyKey) {}
    public record PlaceOrderResponse(String orderId, String status) {}

    private final ComponentClient client;

    public OrderEndpoint(ComponentClient client) {
        this.client = client;
    }

    @Post("/orders")
    public PlaceOrderResponse place(PlaceOrderRequest req) {
        String orderId = (req.idempotencyKey() != null && !req.idempotencyKey().isBlank())
            ? "order-" + req.idempotencyKey()
            : "order-" + UUID.randomUUID();
        String result = client.forWorkflow(orderId)
            .method(OrderWorkflow::start)
            .invoke(new StartOrder(req.customerId(), req.lines()));
        return new PlaceOrderResponse(result, "STARTED");
    }

    @Post("/orders/{orderId}/cancel")
    public String cancel(String orderId) {
        return client.forWorkflow(orderId)
            .method(OrderWorkflow::abandonIfNotPaid)
            .invoke(new CancelIfNotPaid());
    }

    @Get("/orders")
    public OrderRows list() {
        return client.forView().method(OrdersView::all).invoke();
    }

    @Get("/orders/customer/{customerId}")
    public OrderRows byCustomer(String customerId) {
        return client.forView().method(OrdersView::byCustomer).invoke(customerId);
    }

    @Get("/orders/{orderId}")
    public OrderState get(String orderId) {
        return client.forEventSourcedEntity(orderId)
            .method(OrderEntity::get)
            .invoke();
    }

    @Get("/orders/{orderId}/history")
    public List<HistoryEntry> history(String orderId) {
        return client.forView()
            .method(OrdersView::byId)
            .invoke(orderId)
            .history();
    }
}
