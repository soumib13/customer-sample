package com.soumib.akkakata.domain.events;

public sealed interface CustomerEvents {
    
    public record customerCreated(
        String customerId,
        String FirstName,
        String LastName
    ) implements CustomerEvents {}

    public record customerDeleted(
        String customerId,
        String FirstName,
        String LastName
    ) implements CustomerEvents {}

    public record customerUpdated(
        String customerId,
        String FirstName,
        String LastName
    ) implements CustomerEvents {}

}
