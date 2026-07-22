package com.soumib.akkakata.api;

import akka.javasdk.annotations.http.Delete;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.annotations.http.Put;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.annotations.Acl;

import java.util.List;

import com.soumib.akkakata.domain.entities.CustomerEntity;
import com.soumib.akkakata.views.CustomerView;
import com.soumib.akkakata.views.CustomerView.customerEntries;
import com.soumib.akkakata.domain.commands.CustomerCommands.*;


@HttpEndpoint("/api")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class CustomerEndpoint {

    private final ComponentClient client;

    public record CreateCustomerResponse(String customerId, String FirstName, String LastName){}
    public record CreateCustomerRequest(String customerId, String FirstName, String LastName){}
    public record GetAllCustomersResponse( List<CreateCustomerResponse> response){}
    public record UpdateCustomerRequest(String FirstName, String LastName){}
    public record UpdateCustomerResponse(String customerId, String FirstName, String LastName){}

    public CustomerEndpoint(ComponentClient client) {
        this.client = client;
    }

    @Post("/customers")
    public CreateCustomerResponse createCustomer(CreateCustomerRequest request) {
        var customerId = request.customerId;
        var cmd = new CreateCustomer(request.FirstName, request.LastName);
        client.forEventSourcedEntity(customerId)
            .method(CustomerEntity::create)
            .invoke(cmd);

        return new CreateCustomerResponse( customerId, request.FirstName, request.LastName);
    }

    @Put("/customers/{customerId}")
    public UpdateCustomerResponse updateCustomer(String customerId, UpdateCustomerRequest request) {
        var cmd = new UpdateCustomer(request.FirstName, request.LastName);
        client.forEventSourcedEntity(customerId)
            .method(CustomerEntity::update)
            .invoke(cmd);

        return new UpdateCustomerResponse(customerId, request.FirstName, request.LastName);
    }

    @Delete("/customers/{customerId}")
    public String deleteCustomer(String customerId)
    {
        var cmd = new DeleteCustomer();
        client.forEventSourcedEntity(customerId)
            .method(CustomerEntity::delete)
            .invoke(cmd);

        return "Deleted Customer with ID: " + customerId;
    }

    @Get("/customers")
    public customerEntries getCustomers() {

        return client.forView()
            .method(CustomerView::getAll)
            .invoke();
    }

}