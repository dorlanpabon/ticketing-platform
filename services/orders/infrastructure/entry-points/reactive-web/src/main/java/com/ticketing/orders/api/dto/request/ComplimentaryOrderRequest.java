package com.ticketing.orders.api.dto.request;

import java.util.List;

public record ComplimentaryOrderRequest(String eventId, List<String> ticketIds, String beneficiaryId,
    String reason) {
}
