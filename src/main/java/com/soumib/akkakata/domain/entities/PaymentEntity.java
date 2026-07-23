package com.soumib.akkakata.domain.entities;

import akka.javasdk.annotations.ComponentId;
import akka.javasdk.keyvalueentity.KeyValueEntity;

import com.soumib.akkakata.domain.commands.PaymentCommands.RecordPayment;
import com.soumib.akkakata.domain.model.PaymentRecord;

@ComponentId("payment")
public class PaymentEntity extends KeyValueEntity<PaymentRecord> {

    @Override
    public PaymentRecord emptyState() {
        return PaymentRecord.empty();
    }

    public Effect<String> charge(RecordPayment cmd) {
        if (currentState().charged()) {
            return effects().reply("ALREADY_CHARGED");
        }
        if (cmd.amountCents() <= 0) {
            return effects().reply("DECLINED");
        }
        return effects()
            .updateState(new PaymentRecord(cmd.orderId(), cmd.amountCents(), true))
            .thenReply("OK");
    }

    public ReadOnlyEffect<PaymentRecord> get() {
        return effects().reply(currentState());
    }
}
