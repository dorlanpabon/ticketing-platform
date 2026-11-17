package com.ticketing.orders.api.dto.request;

import java.util.List;

public record PurchaseRequest(String eventId, List<String> ticketIds) {
}
