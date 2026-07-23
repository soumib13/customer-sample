package com.soumib.akkakata.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;

import com.soumib.akkakata.domain.commands.InventoryCommands.RestockInventory;
import com.soumib.akkakata.domain.entities.InventoryEntity;
import com.soumib.akkakata.domain.model.InventoryState;

@HttpEndpoint("/api")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class InventoryEndpoint {

    public record RestockRequest(String sku, int qty) {}

    private final ComponentClient client;

    public InventoryEndpoint(ComponentClient client) {
        this.client = client;
    }

    @Get("/inventory")
    public InventoryState get() {
        return client.forKeyValueEntity(InventoryEntity.ID)
            .method(InventoryEntity::get)
            .invoke();
    }

    @Post("/inventory/restock")
    public String restock(RestockRequest req) {
        return client.forKeyValueEntity(InventoryEntity.ID)
            .method(InventoryEntity::restock)
            .invoke(new RestockInventory(req.sku(), req.qty()));
    }
}
