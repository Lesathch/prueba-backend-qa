package com.accenture.franchises.api;

import com.accenture.franchises.domain.InventoryItem;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import static org.assertj.core.api.Assertions.*;

/** Full HTTP stack and real DynamoDB semantics. Never silently skips when Docker is absent. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InventoryApiIT {
    private static final String TABLE = "franchises-it-" + UUID.randomUUID();
    private static GenericContainer<?> container;
    private static DynamoDbAsyncClient database;
    @Autowired Environment environment;
    private WebTestClient api;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        String endpoint = System.getProperty("dynamodb.test.endpoint", "");
        if (endpoint.isBlank()) {
            container = new GenericContainer<>(DockerImageName.parse("amazon/dynamodb-local:3.3.0"))
                    .withExposedPorts(8000).withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb");
            container.start();
            endpoint = "http://" + container.getHost() + ":" + container.getMappedPort(8000);
        }
        String address = endpoint;
        registry.add("inventory.dynamodb.endpoint", () -> address);
        registry.add("inventory.dynamodb.table", () -> TABLE);
        database = DynamoDbAsyncClient.builder().endpointOverride(URI.create(address)).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                .build();
        database.createTable(CreateTableRequest.builder().tableName(TABLE).billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build())
                .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build()).build()).join();
        database.waiter().waitUntilTableExists(DescribeTableRequest.builder().tableName(TABLE).build()).join();
    }

    @BeforeEach
    void bind() {
        api = WebTestClient.bindToServer().baseUrl("http://localhost:" + environment.getProperty("local.server.port"))
                .responseTimeout(Duration.ofSeconds(20)).build();
    }

    @AfterAll
    static void cleanup() {
        if (database != null) {
            database.deleteTable(DeleteTableRequest.builder().tableName(TABLE).build()).join();
            database.close();
        }
        if (container != null) container.stop();
    }

    private InventoryItem create(String path, Object body) {
        var response = api.post().uri(path).bodyValue(body).exchange().expectStatus().isCreated()
                .expectHeader().exists("Location").expectBody(InventoryItem.class).returnResult();
        var item = Objects.requireNonNull(response.getResponseBody());
        assertThat(response.getResponseHeaders().getLocation().toString()).endsWith("/" + item.id());
        return item;
    }
    private InventoryItem franchise() { return create("/api/v1/franchises", Map.of("name", "Franquicia")); }
    private String root(InventoryItem f) { return "/api/v1/franchises/" + f.id(); }
    private String branch(InventoryItem f) {
        return root(f) + "/branches/" + create(root(f) + "/branches", Map.of("name", "Centro")).id();
    }
    private String product(String branch) {
        return branch + "/products/" + create(branch + "/products", Map.of("name", "Café", "stock", 10)).id();
    }

    @Test
    void completeLifecycleWithAllRenameEndpointsAndDelete() {
        var f = franchise();
        var b = branch(f);
        var p = product(b);
        api.get().uri(p).exchange().expectStatus().isOk().expectBody().jsonPath("$.stock").isEqualTo(10);
        for (String path : List.of(root(f), b, p)) {
            api.patch().uri(path + "/name").bodyValue(Map.of("name", " Nuevo nombre ", "version", 0))
                    .exchange().expectStatus().isOk().expectBody().jsonPath("$.name").isEqualTo("Nuevo nombre")
                    .jsonPath("$.version").isEqualTo(1);
        }
        api.patch().uri(p + "/stock").bodyValue(Map.of("stock", 55, "version", 1))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.stock").isEqualTo(55)
                .jsonPath("$.version").isEqualTo(2);
        api.get().uri(root(f) + "/products/max-stock").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$[0].branchName").isEqualTo("Nuevo nombre")
                .jsonPath("$[0].productName").isEqualTo("Nuevo nombre").jsonPath("$[0].stock").isEqualTo(55);
        api.delete().uri(p).exchange().expectStatus().isNoContent().expectBody().isEmpty();
        api.get().uri(p).exchange().expectStatus().isNotFound();
        api.delete().uri(p).exchange().expectStatus().isNotFound();
    }

    @Test
    void rejectsOrphanBranchAndProduct() {
        api.post().uri("/api/v1/franchises/" + UUID.randomUUID() + "/branches").bodyValue(Map.of("name", "Centro"))
                .exchange().expectStatus().isNotFound();
        var f = franchise();
        api.post().uri(root(f) + "/branches/" + UUID.randomUUID() + "/products")
                .bodyValue(Map.of("name", "Café", "stock", 1)).exchange().expectStatus().isNotFound();
    }

    @Test
    void rejectsProductUnderWrongBranchOrFranchise() {
        var f = franchise();
        var b = branch(f);
        var p = product(b);
        var other = branch(f);
        String wrong = other + p.substring(b.length());
        api.get().uri(wrong).exchange().expectStatus().isNotFound();
        api.delete().uri(wrong).exchange().expectStatus().isNotFound();
        api.patch().uri(wrong + "/stock").bodyValue(Map.of("stock", 1, "version", 0))
                .exchange().expectStatus().isNotFound();
        api.get().uri(p.replace(f.id().toString(), UUID.randomUUID().toString())).exchange().expectStatus().isNotFound();
        api.get().uri(p).exchange().expectStatus().isOk();
    }

    @Test
    void validatesNamesJsonAndUuids() {
        for (String name : List.of("", "  ", "x".repeat(121))) {
            api.post().uri("/api/v1/franchises").bodyValue(Map.of("name", name))
                    .exchange().expectStatus().isBadRequest().expectHeader().contentTypeCompatibleWith("application/problem+json");
        }
        api.post().uri("/api/v1/franchises").bodyValue(Map.of()).exchange().expectStatus().isBadRequest();
        api.post().uri("/api/v1/franchises").bodyValue(Map.of("name", "Ok", "extra", true))
                .exchange().expectStatus().isBadRequest();
        api.post().uri("/api/v1/franchises").header("Content-Type", "application/json").bodyValue("{")
                .exchange().expectStatus().isBadRequest();
        api.get().uri("/api/v1/franchises/not-a-uuid").exchange().expectStatus().isBadRequest();
    }

    @Test
    void validatesStockAndVersions() {
        var b = branch(franchise());
        for (Object stock : List.of(-1, 1.5, 2147483648L)) {
            api.post().uri(b + "/products").bodyValue(Map.of("name", "Café", "stock", stock))
                    .exchange().expectStatus().isBadRequest();
        }
        api.post().uri(b + "/products").bodyValue(Map.of("name", "Café")).exchange().expectStatus().isBadRequest();
        var p = product(b);
        for (Object body : List.of(Map.of("stock", -1, "version", 0), Map.of("stock", 1),
                Map.of("stock", 1, "version", -1))) {
            api.patch().uri(p + "/stock").bodyValue(body).exchange().expectStatus().isBadRequest();
        }
        api.patch().uri(p + "/stock").bodyValue(Map.of("stock", 0, "version", 0)).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.stock").isEqualTo(0);
    }

    @Test
    void staleVersionReturnsConflictAndPreservesCommittedStock() {
        var p = product(branch(franchise()));
        api.patch().uri(p + "/stock").bodyValue(Map.of("stock", 25, "version", 0)).exchange().expectStatus().isOk();
        api.patch().uri(p + "/stock").bodyValue(Map.of("stock", 99, "version", 0)).exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.detail").exists();
        api.get().uri(p).exchange().expectStatus().isOk().expectBody().jsonPath("$.stock").isEqualTo(25);
    }

    @Test
    void onlyOneConcurrentWriterWins() {
        var p = product(branch(franchise()));
        var writes = List.of(31, 32).stream().map(stock -> CompletableFuture.supplyAsync(() ->
                api.patch().uri(p + "/stock").bodyValue(Map.of("stock", stock, "version", 0)).exchange()
                        .returnResult(Void.class).getStatus().value())).toList();
        assertThat(writes.stream().map(CompletableFuture::join).toList()).containsExactlyInAnyOrder(200, 409);
        api.get().uri(p).exchange().expectStatus().isOk().expectBody().jsonPath("$.version").isEqualTo(1);
    }

    @Test
    void computesOneMaximumPerNonEmptyBranch() {
        var f = franchise();
        var first = branch(f);
        var second = branch(f);
        branch(f); // Empty branch should be omitted.
        create(first + "/products", Map.of("name", "Menor", "stock", 1));
        var winner = create(first + "/products", Map.of("name", "Mayor", "stock", 100));
        create(second + "/products", Map.of("name", "Segundo", "stock", 8));
        api.get().uri(root(f) + "/products/max-stock").exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[?(@.productName == 'Mayor')].productId").isEqualTo(winner.id().toString())
                .jsonPath("$[?(@.productName == 'Mayor')].stock").isEqualTo(100);
    }

    @Test
    void emptyFranchiseAndMissingFranchiseHaveDifferentResponses() {
        api.get().uri(root(franchise()) + "/products/max-stock").exchange().expectStatus().isOk().expectBody().json("[]");
        api.get().uri("/api/v1/franchises/" + UUID.randomUUID() + "/products/max-stock").exchange().expectStatus().isNotFound();
    }

    @Test
    void readsPastDynamoOneMegabytePageBoundary() {
        var f = franchise();
        var b = branch(f);
        String branchId = b.substring(b.lastIndexOf('/') + 1);
        // Padding is test-only; 100 rows exceed 1 MB without thousands of writes.
        for (int batch = 0; batch < 4; batch++) {
            List<WriteRequest> writes = new ArrayList<>();
            for (int index = batch * 25; index < (batch + 1) * 25; index++) {
                var id = new UUID(0, index + 1).toString();
                Map<String, AttributeValue> data = new HashMap<>();
                Map.of("pk", "FRANCHISE#" + f.id(), "sk", "BRANCH#" + branchId + "#PRODUCT#" + id,
                        "id", id, "name", "Producto " + index, "franchiseId", f.id().toString(),
                        "branchId", branchId, "kind", "PRODUCT", "padding", "x".repeat(16000))
                        .forEach((key, value) -> data.put(key, AttributeValue.builder().s(value).build()));
                data.put("stock", AttributeValue.builder().n(Integer.toString(index)).build());
                data.put("version", AttributeValue.builder().n("0").build());
                writes.add(WriteRequest.builder().putRequest(PutRequest.builder().item(data).build()).build());
            }
            var result = database.batchWriteItem(BatchWriteItemRequest.builder().requestItems(Map.of(TABLE, writes)).build()).join();
            assertThat(result.unprocessedItems()).isEmpty();
        }
        api.get().uri(b + "/products").exchange().expectStatus().isOk().expectBody().jsonPath("$.length()").isEqualTo(100);
        api.get().uri(root(f) + "/products/max-stock").exchange().expectStatus().isOk().expectBody()
                .jsonPath("$[0].stock").isEqualTo(99);
    }

    @Test
    void listsBranchesAndProductsAndExposesHealth() {
        var f = franchise();
        var b = branch(f);
        product(b);
        api.get().uri(root(f) + "/branches").exchange().expectStatus().isOk().expectBody().jsonPath("$.length()").isEqualTo(1);
        api.get().uri(b + "/products").exchange().expectStatus().isOk().expectBody().jsonPath("$.length()").isEqualTo(1);
        api.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("UP");
    }
}
