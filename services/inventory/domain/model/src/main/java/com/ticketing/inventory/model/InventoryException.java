package com.ticketing.inventory.model;

public final class InventoryException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String errorCode;
    private final int responseStatus;

    public InventoryException(String code, int status) {
        super(code);
        this.errorCode = code;
        this.responseStatus = status;
    }

    public String code() {
        return errorCode;
    }

    public int status() {
        return responseStatus;
    }
}
