package com.accenture.franchises.domain;

import java.util.UUID;

/** Immutable representation; versions protect against lost updates. */
public record InventoryItem(UUID id, String name, Integer stock, long version,
                            UUID franchiseId, UUID branchId, Kind kind) {
    public enum Kind { FRANCHISE, BRANCH, PRODUCT }

}
