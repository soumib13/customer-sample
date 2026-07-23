package com.soumib.akkakata.domain.workflows;

import akka.javasdk.annotations.ComponentId;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.workflow.Workflow;

import com.soumib.akkakata.domain.commands.InventoryCommands.ReleaseInventory;
import com.soumib.akkakata.domain.commands.InventoryCommands.ReserveInventory;
import com.soumib.akkakata.domain.commands.OrderCommands.CancelIfNotPaid;
import com.soumib.akkakata.domain.commands.OrderCommands.MarkFailed;
import com.soumib.akkakata.domain.commands.OrderCommands.MarkPaid;
import com.soumib.akkakata.domain.commands.OrderCommands.MarkReserved;
import com.soumib.akkakata.domain.commands.OrderCommands.MarkShipped;
import com.soumib.akkakata.domain.commands.OrderCommands.PlaceOrder;
import com.soumib.akkakata.domain.commands.PaymentCommands.RecordPayment;
import com.soumib.akkakata.domain.entities.InventoryEntity;
import com.soumib.akkakata.domain.entities.OrderEntity;
import com.soumib.akkakata.domain.entities.PaymentEntity;
import com.soumib.akkakata.domain.model.OrderState.OrderLine;

import java.time.Duration;
import java.util.List;

@ComponentId("order-workflow")
public class OrderWorkflow extends Workflow<OrderWorkflow.State> {

    public record State(
        String orderId,
        String customerId,
        List<OrderLine> lines,
        long totalCents
    ) {}

    public record StartOrder(String customerId, List<OrderLine> lines) {}

    private static final Duration ABANDON_TIMEOUT = Duration.ofSeconds(30);
    private static final String ABANDON_TIMER_PREFIX = "abandon-";

    private final ComponentClient client;

    public OrderWorkflow(ComponentClient client) {
        this.client = client;
    }

    @Override
    public WorkflowDef<State> definition() {
        return workflow()
            .addStep(reserveStep(), maxRetries(1).failoverTo("compensate", "workflow:reserve-step-failed"))
            .addStep(chargeStep(), maxRetries(1).failoverTo("compensate", "workflow:charge-step-failed"))
            .addStep(shipStep(), maxRetries(2).failoverTo("compensate", "workflow:ship-step-failed"))
            .addStep(compensateStep())
            .defaultStepTimeout(Duration.ofSeconds(10));
    }

    public Effect<String> start(StartOrder cmd) {
        if (currentState() != null) {
            // Idempotent: a retried request with the same workflow id (derived from an
            // idempotency key) returns the existing order instead of erroring.
            return effects().reply(currentState().orderId());
        }
        String orderId = commandContext().workflowId();
        long total = cmd.lines().stream()
            .mapToLong(l -> l.unitPriceCents() * l.qty())
            .sum();

        // Record the order in its own event-sourced entity.
        client.forEventSourcedEntity(orderId)
            .method(OrderEntity::place)
            .invoke(new PlaceOrder(cmd.customerId(), cmd.lines()));

        // Schedule an auto-cancel timer for abandoned carts. This must go through the
        // workflow (not straight to the entity) so that, if inventory was already
        // reserved, it actually gets released instead of being orphaned.
        timers().createSingleTimer(
            ABANDON_TIMER_PREFIX + orderId,
            ABANDON_TIMEOUT,
            client.forWorkflow(orderId)
                .method(OrderWorkflow::abandonIfNotPaid)
                .deferred(new CancelIfNotPaid())
        );

        return effects()
            .updateState(new State(orderId, cmd.customerId(), cmd.lines(), total))
            .transitionTo("reserve")
            .thenReply(orderId);
    }

    public Effect<String> abandonIfNotPaid(CancelIfNotPaid cmd) {
        if (currentState() == null) {
            return effects().error("Order not found");
        }
        String result = client.forEventSourcedEntity(currentState().orderId())
            .method(OrderEntity::cancelIfNotPaid)
            .invoke(cmd);

        if (!"CANCELLED".equals(result)) {
            return effects().reply(result);
        }

        client.forKeyValueEntity(InventoryEntity.ID)
            .method(InventoryEntity::release)
            .invoke(new ReleaseInventory(currentState().orderId()));

        return effects().end().thenReply(result);
    }

    private Step reserveStep() {
        return step("reserve")
            .call(() ->
                client.forKeyValueEntity(InventoryEntity.ID)
                    .method(InventoryEntity::reserve)
                    .invoke(new ReserveInventory(currentState().orderId(), currentState().lines()))
            )
            .andThen(String.class, result -> {
                if ("OK".equals(result)) {
                    client.forEventSourcedEntity(currentState().orderId())
                        .method(OrderEntity::markReserved)
                        .invoke(new MarkReserved());
                    return effects().transitionTo("charge");
                }
                return effects().transitionTo("compensate", "inventory:" + result);
            });
    }

    private Step chargeStep() {
        return step("charge")
            .call(() -> {
                String result = client.forKeyValueEntity(currentState().orderId())
                    .method(PaymentEntity::charge)
                    .invoke(new RecordPayment(currentState().orderId(), currentState().totalCents()));
                // Cancel the abandon timer here (in the step action, not in andThen —
                // timer ops are only valid inside command handlers or step actions).
                if ("OK".equals(result)) {
                    timers().delete(ABANDON_TIMER_PREFIX + currentState().orderId());
                }
                return result;
            })
            .andThen(String.class, result -> {
                if ("OK".equals(result)) {
                    client.forEventSourcedEntity(currentState().orderId())
                        .method(OrderEntity::markPaid)
                        .invoke(new MarkPaid());
                    return effects().transitionTo("ship");
                }
                return effects().transitionTo("compensate", "payment:" + result);
            });
    }

    private Step shipStep() {
        return step("ship")
            .call(() -> {
                client.forEventSourcedEntity(currentState().orderId())
                    .method(OrderEntity::markShipped)
                    .invoke(new MarkShipped());
                return "SHIPPED";
            })
            .andThen(String.class, __ -> effects().end());
    }

    private Step compensateStep() {
        return step("compensate")
            .call(String.class, reason -> {
                client.forKeyValueEntity(InventoryEntity.ID)
                    .method(InventoryEntity::release)
                    .invoke(new ReleaseInventory(currentState().orderId()));

                client.forEventSourcedEntity(currentState().orderId())
                    .method(OrderEntity::markFailed)
                    .invoke(new MarkFailed(reason));

                timers().delete(ABANDON_TIMER_PREFIX + currentState().orderId());
                return reason;
            })
            .andThen(String.class, __ -> effects().end());
    }
}
