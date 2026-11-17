package com.ticketing.inventory.api.dto.response;

import java.util.List;

public record TicketPageResponse(List<TicketResponse> items, String nextCursor) {
    public TicketPageResponse {
        items = List.copyOf(items);
    }
}
