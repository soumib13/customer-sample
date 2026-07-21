package com.pradeepl.akkakata.domain.commands;

public final class CustomerCommands {
    public record CreateCustomer(
        String firstName,
        String lastName
    ){}

    public record DeleteCustomer(){
        
    }
}
