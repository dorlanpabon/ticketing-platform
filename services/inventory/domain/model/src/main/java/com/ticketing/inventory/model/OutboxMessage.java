package com.ticketing.inventory.model;

public record OutboxMessage(String id, String destination, String body, String leaseId) {
}
