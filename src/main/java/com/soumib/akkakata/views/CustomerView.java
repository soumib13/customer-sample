package com.soumib.akkakata.views;

import java.util.List;

import com.soumib.akkakata.domain.entities.CustomerEntity;
import com.soumib.akkakata.domain.events.CustomerEvents.customerCreated;
import com.soumib.akkakata.domain.events.CustomerEvents.customerDeleted;

import akka.javasdk.annotations.ComponentId;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.annotations.Table;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;

@ComponentId("customerview")
public class CustomerView extends View{

    public record customerEntry(String customerId, String FirstName, String LastName, boolean deleted){}
    public record customerEntries(List<customerEntry> entries) {}

    @Query("SELECT * AS entries FROM customers_table")
    public QueryEffect<customerEntries> getAll() {
         return queryResult();
    }

    @Table("customers_table")
     @Consume.FromEventSourcedEntity(CustomerEntity.class)
    public static class CustomersUpdater extends TableUpdater<customerEntry> {
        
        public Effect<customerEntry> onEvent(customerCreated event) {
            return effects().updateRow(new customerEntry(event.customerId(), event.FirstName(),event.LastName(), false));
        }

        public Effect<customerEntry> onEvent(customerDeleted event) {
            return effects().updateRow(new customerEntry(event.customerId(), event.FirstName(),event.LastName(), true));
        }

    }
}
