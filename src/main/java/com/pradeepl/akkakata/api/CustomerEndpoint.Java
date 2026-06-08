package com.pradeepl.akkakata.api;


@HttpEndpoint("/api/customers")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class CustomerEndpoint extends AbstractHttpEndpoint{

    private final ComponentClient client;

    public CustomerEndpoint(ComponentClient client) {
        this.client = client;
    }

    Post("/")
    public CreateCustomerResponse createApplication(CreateCustomerRequest request) {
        var customerId = request.customerId;
        client.forEventSourcedEntity(customerId)
            .method(CustomerEntity::create)
            .invoke(cmd);
    }

    public record CreateCustomerResponse(){}
    public record CreateCustomerRequest(String customerId, String FirstName, String LastName){}


}