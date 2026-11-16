package com.ticketing.catalog.api.dto.response;

import java.util.List;

public record EventPageResponse(List<EventResponse> items, String cursor) {
}
