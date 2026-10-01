package com.accenture.franchises.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;

@Component("dynamodb")
public class DynamoHealthIndicator implements ReactiveHealthIndicator {
    private final DynamoDbAsyncClient client;
    private final String table;
    public DynamoHealthIndicator(DynamoDbAsyncClient client, @Value("${inventory.dynamodb.table}") String table) {
        this.client = client;
        this.table = table;
    }
    @Override
    public Mono<Health> health() {
        return Mono.fromFuture(() -> client.describeTable(DescribeTableRequest.builder().tableName(table).build()))
                .map(ignored -> Health.up().build()).onErrorResume(error -> Mono.just(Health.down().build()));
    }
}
