package com.pradeepl.akkakata.domain.events;

public sealed interface CustomerEvents {

    
    public record customerCreated(
        String customerId,
        String FirstName,
        String LastName
    ) implements CustomerEvents {}

}
