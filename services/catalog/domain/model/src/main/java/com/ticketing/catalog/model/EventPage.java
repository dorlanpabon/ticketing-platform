package com.ticketing.catalog.model;

import java.util.List;

public record EventPage(List<Event> items, String cursor) {
    public EventPage {
        items = List.copyOf(items);
    }
}
