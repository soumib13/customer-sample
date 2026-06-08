package com.pradeepl.akkakata.domain.events;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.pradeepl.akkakata.domain.commands.CustomerCommands.CreateCustomer;

import akka.javasdk.eventsourcedentity.EventSourcedEntity.Effect;

public sealed interface CustomerEvents {

    
    public record customerCreated(
        String customerId,
        String FirstName,
        String LastName
    ) implements CustomerEvents {}

    

    
}
