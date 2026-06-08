package com.pradeepl.akkakata.domain.entities;

import akka.javasdk.annotations.ComponentId;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;

import java.time.Instant;

import com.pradeepl.akkakata.domain.commands.CustomerCommands.CreateCustomer;
import com.pradeepl.akkakata.domain.events.CustomerEvents;
import com.pradeepl.akkakata.domain.events.CustomerEvents.customerCreated;
import com.pradeepl.akkakata.domain.model.CustomerState;

@ComponentId("Customer")
public class CustomerEntity extends EventSourcedEntity<CustomerState, CustomerEvents>{

  @Override
  public CustomerState emptyState(){
    return CustomerState.empty();
  }

  public Effect<String> create(CreateCustomer cmd) {

      if (currentState().customerId() != null) {
        return effects().error("Customer already exists");
      }

      String customerId = commandContext().entityId();
      var event = new customerCreated(customerId, cmd.firstName(), cmd.lastName());
            
      

    return effects()
      .persist(event)
      .thenReply(__ -> customerId);

  }

  @Override
  public CustomerState applyEvent(CustomerEvents event) {
    
    return switch (event) {
      case customerCreated e -> new CustomerState( e.customerId(), e.FirstName(), e.LastName());
    };
  }  
}