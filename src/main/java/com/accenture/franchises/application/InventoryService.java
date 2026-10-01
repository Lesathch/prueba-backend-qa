package com.accenture.franchises.application;

import com.accenture.franchises.domain.*;
import java.util.*;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static com.accenture.franchises.domain.InventoryItem.Kind.*;

@Service
public class InventoryService {
    private final InventoryRepository repository;

    public InventoryService(InventoryRepository repository) { this.repository = repository; }

    public Mono<InventoryItem> createFranchise(String name) {
        return Mono.defer(() -> {
            UUID id = UUID.randomUUID();
            return repository.create(new InventoryItem(id, name.strip(), null, 0, id, null, FRANCHISE));
        });
    }

    public Mono<InventoryItem> createBranch(UUID franchise, String name) {
        return Mono.defer(() -> repository.create(new InventoryItem(UUID.randomUUID(), name.strip(), null,
                0, franchise, null, BRANCH)));
    }

    public Mono<InventoryItem> createProduct(UUID franchise, UUID branch, String name, int stock) {
        return Mono.defer(() -> repository.create(new InventoryItem(UUID.randomUUID(), name.strip(), stock,
                0, franchise, branch, PRODUCT)));
    }

    public Mono<InventoryItem> get(UUID franchise, UUID branch, UUID product) {
        return repository.get(franchise, branch, product);
    }

    public Flux<InventoryItem> branches(UUID franchise) {
        return get(franchise, null, null).flatMapMany(ignored -> repository.list(franchise))
                .filter(item -> item.kind() == BRANCH);
    }

    public Flux<InventoryItem> products(UUID franchise, UUID branch) {
        return get(franchise, branch, null).flatMapMany(ignored -> repository.list(franchise))
                .filter(item -> item.kind() == PRODUCT && branch.equals(item.branchId()));
    }

    public Mono<InventoryItem> rename(UUID franchise, UUID branch, UUID product, String name, long version) {
        return repository.rename(franchise, branch, product, name.strip(), version);
    }

    public Mono<InventoryItem> stock(UUID franchise, UUID branch, UUID product, int stock, long version) {
        return repository.stock(franchise, branch, product, stock, version);
    }

    public Mono<Void> delete(UUID franchise, UUID branch, UUID product) {
        return repository.delete(franchise, branch, product);
    }

    public record TopStock(UUID branchId, String branchName, UUID productId, String productName,
                           int stock, long version) {}

    /** One query over all pages, O(items) memory/time; no request per branch. */
    public Mono<List<TopStock>> topStock(UUID franchise) {
        return get(franchise, null, null).then(repository.list(franchise).collectList()).map(items -> {
            Map<UUID, InventoryItem> branches = new HashMap<>();
            Map<UUID, InventoryItem> winners = new HashMap<>();
            Comparator<InventoryItem> rank = Comparator.comparingInt(InventoryItem::stock)
                    .thenComparing(item -> item.id().toString(), Comparator.reverseOrder());
            items.forEach(item -> {
                if (item.kind() == BRANCH) branches.put(item.id(), item);
                if (item.kind() == PRODUCT) winners.merge(item.branchId(), item,
                        (a, b) -> rank.compare(a, b) >= 0 ? a : b);
            });
            return winners.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)))
                    .map(entry -> new TopStock(entry.getKey(), branches.get(entry.getKey()).name(),
                            entry.getValue().id(), entry.getValue().name(), entry.getValue().stock(),
                            entry.getValue().version())).toList();
        });
    }
}
