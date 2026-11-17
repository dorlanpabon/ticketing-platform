package com.ticketing.catalog.api.mapper;

import com.ticketing.catalog.api.dto.response.EventPageResponse;
import com.ticketing.catalog.api.dto.response.EventResponse;
import com.ticketing.catalog.model.Event;
import com.ticketing.catalog.model.EventPage;

import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface EventResponseMapper {

    EventResponse toResponse(Event event);

    EventPageResponse toResponse(EventPage page);
}
