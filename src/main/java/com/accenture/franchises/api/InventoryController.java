package com.accenture.franchises.api;

import com.accenture.franchises.application.InventoryService;
import com.accenture.franchises.domain.InventoryItem;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/franchises")
public class InventoryController {
    private final InventoryService service;
    public InventoryController(InventoryService service) { this.service = service; }

    private ResponseEntity<InventoryItem> created(InventoryItem item, String path) {
        return ResponseEntity.created(URI.create(path + "/" + item.id())).body(item);
    }
    private static String root(UUID franchise) { return "/api/v1/franchises/" + franchise; }
    private static String branchPath(UUID franchise, UUID branch) { return root(franchise) + "/branches/" + branch; }

    @PostMapping
    Mono<ResponseEntity<InventoryItem>> franchise(@Valid @RequestBody Requests.CreateName request) {
        return service.createFranchise(request.name()).map(item -> created(item, "/api/v1/franchises"));
    }
    @GetMapping("/{franchise}")
    Mono<InventoryItem> franchise(@PathVariable UUID franchise) { return service.get(franchise, null, null); }
    @PostMapping("/{franchise}/branches")
    Mono<ResponseEntity<InventoryItem>> branch(@PathVariable UUID franchise, @Valid @RequestBody Requests.CreateName request) {
        return service.createBranch(franchise, request.name()).map(item -> created(item, root(franchise) + "/branches"));
    }
    @GetMapping("/{franchise}/branches")
    Mono<List<InventoryItem>> branches(@PathVariable UUID franchise) { return service.branches(franchise).collectList(); }
    @GetMapping("/{franchise}/branches/{branch}")
    Mono<InventoryItem> branch(@PathVariable UUID franchise, @PathVariable UUID branch) {
        return service.get(franchise, branch, null);
    }
    @PostMapping("/{franchise}/branches/{branch}/products")
    Mono<ResponseEntity<InventoryItem>> product(@PathVariable UUID franchise, @PathVariable UUID branch,
                                              @Valid @RequestBody Requests.CreateProduct request) {
        return service.createProduct(franchise, branch, request.name(), request.stock())
                .map(item -> created(item, branchPath(franchise, branch) + "/products"));
    }
    @GetMapping("/{franchise}/branches/{branch}/products")
    Mono<List<InventoryItem>> products(@PathVariable UUID franchise, @PathVariable UUID branch) {
        return service.products(franchise, branch).collectList();
    }
    @GetMapping("/{franchise}/branches/{branch}/products/{product}")
    Mono<InventoryItem> product(@PathVariable UUID franchise, @PathVariable UUID branch, @PathVariable UUID product) {
        return service.get(franchise, branch, product);
    }
    @DeleteMapping("/{franchise}/branches/{branch}/products/{product}")
    Mono<ResponseEntity<Void>> delete(@PathVariable UUID franchise, @PathVariable UUID branch, @PathVariable UUID product) {
        return service.delete(franchise, branch, product).thenReturn(ResponseEntity.noContent().build());
    }
    @PatchMapping("/{franchise}/name")
    Mono<InventoryItem> renameFranchise(@PathVariable UUID franchise, @Valid @RequestBody Requests.Rename request) {
        return service.rename(franchise, null, null, request.name(), request.version());
    }
    @PatchMapping("/{franchise}/branches/{branch}/name")
    Mono<InventoryItem> renameBranch(@PathVariable UUID franchise, @PathVariable UUID branch,
                                     @Valid @RequestBody Requests.Rename request) {
        return service.rename(franchise, branch, null, request.name(), request.version());
    }
    @PatchMapping("/{franchise}/branches/{branch}/products/{product}/name")
    Mono<InventoryItem> renameProduct(@PathVariable UUID franchise, @PathVariable UUID branch, @PathVariable UUID product,
                                     @Valid @RequestBody Requests.Rename request) {
        return service.rename(franchise, branch, product, request.name(), request.version());
    }
    @PatchMapping("/{franchise}/branches/{branch}/products/{product}/stock")
    Mono<InventoryItem> stock(@PathVariable UUID franchise, @PathVariable UUID branch, @PathVariable UUID product,
                             @Valid @RequestBody Requests.Stock request) {
        return service.stock(franchise, branch, product, request.stock(), request.version());
    }
    @GetMapping("/{franchise}/products/max-stock")
    Mono<List<InventoryService.TopStock>> topStock(@PathVariable UUID franchise) { return service.topStock(franchise); }
}
