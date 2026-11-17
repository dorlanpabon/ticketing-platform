package com.ticketing.orders.model;

public final class DomainFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int responseStatus;
    private final String errorCode;

    public DomainFailure(int status, String code) {
        super(code);
        this.responseStatus = status;
        this.errorCode = code;
    }

    public int status() {
        return responseStatus;
    }

    public String code() {
        return errorCode;
    }
}
