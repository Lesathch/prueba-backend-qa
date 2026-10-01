package com.accenture.franchises.domain;

import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface InventoryRepository {
    Mono<InventoryItem> create(InventoryItem item);
    Mono<InventoryItem> get(UUID franchiseId, UUID branchId, UUID productId);
    Flux<InventoryItem> list(UUID franchiseId);
    Mono<InventoryItem> rename(UUID franchiseId, UUID branchId, UUID productId, String name, long version);
    Mono<InventoryItem> stock(UUID franchiseId, UUID branchId, UUID productId, int stock, long version);
    Mono<Void> delete(UUID franchiseId, UUID branchId, UUID productId);
}
