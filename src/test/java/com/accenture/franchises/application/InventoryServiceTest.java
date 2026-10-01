package com.accenture.franchises.application;

import com.accenture.franchises.domain.*;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.accenture.franchises.domain.InventoryItem.Kind.*;

class InventoryServiceTest {
    private final InventoryRepository repository = mock(InventoryRepository.class);
    private final InventoryService service = new InventoryService(repository);
    private final UUID franchise = UUID.randomUUID();
    private final UUID branch = UUID.randomUUID();
    private final InventoryItem root = new InventoryItem(franchise, "Franquicia", null, 0, franchise, null, FRANCHISE);
    private final InventoryItem store = new InventoryItem(branch, "Centro", null, 0, franchise, null, BRANCH);

    @BeforeEach
    void setup() { when(repository.get(franchise, null, null)).thenReturn(Mono.just(root)); }

    private InventoryItem product(String id, int stock) {
        return new InventoryItem(UUID.fromString(id), "Producto", stock, 0, franchise, branch, PRODUCT);
    }

    @Test
    void choosesMaximumAndIncludesBranchName() {
        var low = product("00000000-0000-0000-0000-000000000001", 5);
        var high = product("00000000-0000-0000-0000-000000000002", 30);
        when(repository.list(franchise)).thenReturn(Flux.just(root, store, low, high));
        StepVerifier.create(service.topStock(franchise)).assertNext(result -> {
            assertThat(result).hasSize(1);
            assertThat(result.getFirst().productId()).isEqualTo(high.id());
            assertThat(result.getFirst().branchName()).isEqualTo("Centro");
        }).verifyComplete();
    }

    @Test
    void resolvesTiesByLexicographicallySmallestUuidRegardlessOfInputOrder() {
        var first = product("00000000-0000-0000-0000-000000000001", 0);
        var second = product("00000000-0000-0000-0000-000000000002", 0);
        when(repository.list(franchise)).thenReturn(Flux.just(second, store, first));
        StepVerifier.create(service.topStock(franchise)).assertNext(result ->
                assertThat(result.getFirst().productId()).isEqualTo(first.id())).verifyComplete();
    }

    @Test
    void omitsEmptyBranches() {
        when(repository.list(franchise)).thenReturn(Flux.just(root, store));
        StepVerifier.create(service.topStock(franchise)).assertNext(result -> assertThat(result).isEmpty()).verifyComplete();
    }

    @Test
    void rejectsMissingFranchiseBeforeQueryingInventory() {
        when(repository.get(franchise, null, null)).thenReturn(Mono.error(InventoryException.notFound()));
        when(repository.list(franchise)).thenReturn(Flux.empty());
        StepVerifier.create(service.topStock(franchise)).expectError(InventoryException.class).verify();
    }

    @Test
    void isolatesProductsByBranch() {
        when(repository.get(franchise, branch, null)).thenReturn(Mono.just(store));
        var own = product("00000000-0000-0000-0000-000000000001", 4);
        var other = new InventoryItem(UUID.randomUUID(), "Ajeno", 9, 0, franchise, UUID.randomUUID(), PRODUCT);
        when(repository.list(franchise)).thenReturn(Flux.just(own, other, store));
        StepVerifier.create(service.products(franchise, branch)).expectNext(own).verifyComplete();
    }

    @Test
    void defersCreateUntilSubscriptionAndNormalizesName() {
        when(repository.create(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        var creation = service.createFranchise("  Mi franquicia  ");
        verify(repository, never()).create(any());
        StepVerifier.create(creation).assertNext(item -> {
            assertThat(item.name()).isEqualTo("Mi franquicia");
            assertThat(item.version()).isZero();
        }).verifyComplete();
    }
}
