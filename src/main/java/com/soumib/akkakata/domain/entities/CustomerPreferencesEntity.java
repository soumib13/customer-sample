package com.pradeepl.akkakata.domain.entities;

import akka.javasdk.annotations.ComponentId;
import akka.javasdk.keyvalueentity.KeyValueEntity;

import com.pradeepl.akkakata.domain.commands.PreferencesCommands.SetPreferences;
import com.pradeepl.akkakata.domain.model.CustomerPreferences;

@ComponentId("customer-preferences")
public class CustomerPreferencesEntity extends KeyValueEntity<CustomerPreferences> {

    @Override
    public CustomerPreferences emptyState() {
        return CustomerPreferences.defaults();
    }

    public Effect<String> set(SetPreferences cmd) {
        var next = new CustomerPreferences(
            cmd.theme(),
            cmd.locale(),
            cmd.marketingOptIn()
        );
        return effects().updateState(next).thenReply("OK");
    }

    public Effect<CustomerPreferences> get() {
        return effects().reply(currentState());
    }
}
