package com.accenture.franchises.infrastructure;

import com.accenture.franchises.domain.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import static com.accenture.franchises.domain.InventoryItem.Kind.*;

@Repository
public class DynamoInventoryRepository implements InventoryRepository {
    private final DynamoDbAsyncClient client;
    private final String table;

    public DynamoInventoryRepository(DynamoDbAsyncClient client,
                                    @Value("${inventory.dynamodb.table}") String table) {
        this.client = client;
        this.table = table;
    }

    private static AttributeValue s(Object value) { return AttributeValue.builder().s(value.toString()).build(); }
    private static AttributeValue n(long value) { return AttributeValue.builder().n(Long.toString(value)).build(); }

    private static Map<String, AttributeValue> key(UUID franchise, UUID branch, UUID product) {
        String sort = branch == null ? "META" : "BRANCH#" + branch;
        if (product != null) sort += "#PRODUCT#" + product;
        return Map.of("pk", s("FRANCHISE#" + franchise), "sk", s(sort));
    }

    private static Map<String, AttributeValue> attributes(InventoryItem item) {
        UUID branch = item.kind() == BRANCH ? item.id() : item.branchId();
        UUID product = item.kind() == PRODUCT ? item.id() : null;
        var result = new HashMap<>(key(item.franchiseId(), branch, product));
        result.put("id", s(item.id()));
        result.put("name", s(item.name()));
        result.put("kind", s(item.kind()));
        result.put("version", n(item.version()));
        result.put("franchiseId", s(item.franchiseId()));
        if (item.branchId() != null) result.put("branchId", s(item.branchId()));
        if (item.stock() != null) result.put("stock", n(item.stock()));
        return result;
    }

    private static InventoryItem decode(Map<String, AttributeValue> data) {
        return new InventoryItem(UUID.fromString(data.get("id").s()), data.get("name").s(),
                data.containsKey("stock") ? Integer.parseInt(data.get("stock").n()) : null,
                Long.parseLong(data.get("version").n()), UUID.fromString(data.get("franchiseId").s()),
                data.containsKey("branchId") ? UUID.fromString(data.get("branchId").s()) : null,
                InventoryItem.Kind.valueOf(data.get("kind").s()));
    }

    @Override
    public Mono<InventoryItem> create(InventoryItem item) {
        if (item.kind() == FRANCHISE) {
            var request = PutItemRequest.builder().tableName(table).item(attributes(item))
                    .conditionExpression("attribute_not_exists(pk)").build();
            return Mono.fromFuture(() -> client.putItem(request)).thenReturn(item)
                    .onErrorMap(ConditionalCheckFailedException.class, ignored -> InventoryException.conflict());
        }
        // Parent check and insert commit atomically: orphan branches/products cannot be created.
        var parent = key(item.franchiseId(), item.kind() == PRODUCT ? item.branchId() : null, null);
        var request = TransactWriteItemsRequest.builder().transactItems(
                TransactWriteItem.builder().conditionCheck(ConditionCheck.builder().tableName(table)
                        .key(parent).conditionExpression("attribute_exists(pk)").build()).build(),
                TransactWriteItem.builder().put(Put.builder().tableName(table).item(attributes(item))
                        .conditionExpression("attribute_not_exists(pk)").build()).build()).build();
        return Mono.fromFuture(() -> client.transactWriteItems(request)).thenReturn(item)
                .onErrorMap(TransactionCanceledException.class, error -> {
                    var reasons = error.cancellationReasons();
                    if (!reasons.isEmpty() && "ConditionalCheckFailed".equals(reasons.getFirst().code()))
                        return InventoryException.notFound();
                    if (reasons.size() > 1 && "ConditionalCheckFailed".equals(reasons.get(1).code()))
                        return InventoryException.conflict();
                    return error;
                });
    }

    @Override
    public Mono<InventoryItem> get(UUID franchise, UUID branch, UUID product) {
        var request = GetItemRequest.builder().tableName(table).key(key(franchise, branch, product))
                .consistentRead(true).build();
        return Mono.fromFuture(() -> client.getItem(request)).flatMap(response -> response.hasItem()
                ? Mono.just(decode(response.item())) : Mono.error(InventoryException.notFound()));
    }

    @Override
    public Flux<InventoryItem> list(UUID franchise) {
        var request = QueryRequest.builder().tableName(table).consistentRead(true)
                .keyConditionExpression("pk = :pk").expressionAttributeValues(
                        Map.of(":pk", s("FRANCHISE#" + franchise))).build();
        // Publisher fetches every DynamoDB page; unlike query(), it does not stop at 1 MB.
        return Flux.defer(() -> Flux.from(client.queryPaginator(request)))
                .concatMapIterable(QueryResponse::items).map(DynamoInventoryRepository::decode);
    }

    @Override
    public Mono<InventoryItem> rename(UUID franchise, UUID branch, UUID product, String name, long version) {
        return update(franchise, branch, product, "name", s(name), version);
    }

    @Override
    public Mono<InventoryItem> stock(UUID franchise, UUID branch, UUID product, int stock, long version) {
        return update(franchise, branch, product, "stock", n(stock), version);
    }

    private Mono<InventoryItem> update(UUID franchise, UUID branch, UUID product, String field,
                                       AttributeValue value, long version) {
        var request = UpdateItemRequest.builder().tableName(table).key(key(franchise, branch, product))
                .updateExpression("SET #field = :value, #version = #version + :one")
                .conditionExpression("attribute_exists(pk) AND #version = :expected")
                .expressionAttributeNames(Map.of("#field", field, "#version", "version"))
                .expressionAttributeValues(Map.of(":value", value, ":one", n(1), ":expected", n(version)))
                .returnValues(ReturnValue.ALL_NEW).build();
        return Mono.fromFuture(() -> client.updateItem(request)).map(response -> decode(response.attributes()))
                .onErrorResume(ConditionalCheckFailedException.class, ignored ->
                        get(franchise, branch, product).flatMap(existing -> Mono.error(InventoryException.conflict())));
    }

    @Override
    public Mono<Void> delete(UUID franchise, UUID branch, UUID product) {
        var request = DeleteItemRequest.builder().tableName(table).key(key(franchise, branch, product))
                .conditionExpression("attribute_exists(pk)").build();
        return Mono.fromFuture(() -> client.deleteItem(request)).then()
                .onErrorMap(ConditionalCheckFailedException.class, ignored -> InventoryException.notFound());
    }
}
