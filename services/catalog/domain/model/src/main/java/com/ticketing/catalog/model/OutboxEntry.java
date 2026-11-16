package com.ticketing.catalog.model;

public record OutboxEntry(String key, String leaseOwner, Envelope envelope) {
}
