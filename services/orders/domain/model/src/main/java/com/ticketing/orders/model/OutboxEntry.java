package com.ticketing.orders.model;

public record OutboxEntry(String key, String leaseOwner, Envelope envelope) {
}
