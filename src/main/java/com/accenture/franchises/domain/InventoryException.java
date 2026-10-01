package com.accenture.franchises.domain;

public class InventoryException extends RuntimeException {
    private final boolean conflict;

    private InventoryException(String message, boolean conflict) {
        super(message);
        this.conflict = conflict;
    }

    public boolean isConflict() { return conflict; }
    public static InventoryException notFound() {
        return new InventoryException("El recurso no existe dentro de la franquicia y sucursal indicadas.", false);
    }
    public static InventoryException conflict() {
        return new InventoryException("La versión cambió. Consulta el recurso y reintenta con su versión actual.", true);
    }
}
