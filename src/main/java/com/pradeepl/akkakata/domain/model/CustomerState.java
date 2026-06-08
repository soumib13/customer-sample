package com.pradeepl.akkakata.domain.model;

public record CustomerState(
    String customerId,
    String FirstName,
    String LastName
) {
    public static CustomerState empty () {
        return new CustomerState(null, null, null);
    }
}