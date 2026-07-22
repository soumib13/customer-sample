package com.soumib.akkakata.domain.entities;

import akka.javasdk.annotations.ComponentId;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;

import com.soumib.akkakata.domain.commands.CustomerCommands.CreateCustomer;
import com.soumib.akkakata.domain.commands.CustomerCommands.DeleteCustomer;
import com.soumib.akkakata.domain.commands.CustomerCommands.UpdateCustomer;
import com.soumib.akkakata.domain.events.CustomerEvents;
import com.soumib.akkakata.domain.events.CustomerEvents.customerCreated;
import com.soumib.akkakata.domain.events.CustomerEvents.customerDeleted;
import com.soumib.akkakata.domain.events.CustomerEvents.customerUpdated;
import com.soumib.akkakata.domain.model.CustomerState;

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

  public Effect<CustomerState> get() {
    return effects().reply(currentState());
  }

  public Effect<String> update(UpdateCustomer cmd) {
    if (currentState().customerId() == null || currentState().deleted()) {
      return effects().error("Customer not found");
    }

    var id = currentState().customerId();
    var event = new customerUpdated(id, cmd.firstName(), cmd.lastName());

    return effects().persist(event).thenReply(__ -> id);
  }

  public Effect<String> delete(DeleteCustomer cmd) {
    if (currentState().customerId() == null) {
      return effects().error("Customer not found");
    }

    var id = currentState().customerId();
    var firstName = currentState().FirstName();
    var lastName = currentState().LastName();
    var event = new customerDeleted(id, firstName, lastName);

    return effects().persist(event).thenReply(__ -> id);
  }

  @Override
  public CustomerState applyEvent(CustomerEvents event) {
    
    return switch (event) {
      case customerCreated e -> new CustomerState( e.customerId(), e.FirstName(), e.LastName(), false);
      case customerDeleted e -> new CustomerState ( e.customerId(), e.FirstName(), e.LastName(), true);
      case customerUpdated e -> new CustomerState ( e.customerId(), e.FirstName(), e.LastName(), false);
    };
  }  
}