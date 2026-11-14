package com.ticketing.inventory.model;

import java.util.List;

public record TicketPage(List<Ticket> items, String nextCursor) {
    public TicketPage { items = List.copyOf(items); }
}
