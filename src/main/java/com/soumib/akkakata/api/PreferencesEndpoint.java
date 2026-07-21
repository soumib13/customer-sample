package com.pradeepl.akkakata.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Put;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpException;

import com.pradeepl.akkakata.domain.commands.PreferencesCommands.SetPreferences;
import com.pradeepl.akkakata.domain.entities.CustomerEntity;
import com.pradeepl.akkakata.domain.entities.CustomerPreferencesEntity;
import com.pradeepl.akkakata.domain.model.CustomerPreferences;

@HttpEndpoint("/api")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class PreferencesEndpoint {

    private final ComponentClient client;

    public PreferencesEndpoint(ComponentClient client) {
        this.client = client;
    }

    @Put("/customers/{customerId}/preferences")
    public String setPreferences(String customerId, SetPreferences cmd) {
        requireExistingCustomer(customerId);
        return client.forKeyValueEntity(customerId)
            .method(CustomerPreferencesEntity::set)
            .invoke(cmd);
    }

    @Get("/customers/{customerId}/preferences")
    public CustomerPreferences getPreferences(String customerId) {
        requireExistingCustomer(customerId);
        return client.forKeyValueEntity(customerId)
            .method(CustomerPreferencesEntity::get)
            .invoke();
    }

    private void requireExistingCustomer(String customerId) {
        var customer = client.forEventSourcedEntity(customerId)
            .method(CustomerEntity::get)
            .invoke();

        if (customer.customerId() == null || customer.deleted()) {
            throw HttpException.notFound();
        }
    }
}
