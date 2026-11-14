package com.ticketing.catalog.model;

public final class DomainFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int statusValue;
    private final String errorCode;
    public DomainFailure(int status, String code) { super(code); this.statusValue=status; this.errorCode=code; }
    public int status() { return statusValue; }
    public String code() { return errorCode; }
}
