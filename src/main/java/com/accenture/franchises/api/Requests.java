package com.accenture.franchises.api;

import jakarta.validation.constraints.*;

public final class Requests {
    private Requests() {}
    public record CreateName(@NotBlank @Size(max = 120) String name) {}
    public record CreateProduct(@NotBlank @Size(max = 120) String name,
                                @NotNull @Min(0) Integer stock) {}
    public record Rename(@NotBlank @Size(max = 120) String name,
                         @NotNull @Min(0) Long version) {}
    public record Stock(@NotNull @Min(0) Integer stock, @NotNull @Min(0) Long version) {}
}
