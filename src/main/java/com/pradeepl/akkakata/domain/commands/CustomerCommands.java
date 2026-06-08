package com.pradeepl.akkakata.domain.commands;

public class CustomerCommands {
    public record CreateCustomer(
        String firstName,
        String lastName
    ){}
}
