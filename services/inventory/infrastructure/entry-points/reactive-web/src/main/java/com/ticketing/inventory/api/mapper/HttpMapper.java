package com.ticketing.inventory.api.mapper;

import static org.mapstruct.MappingConstants.ComponentModel.SPRING;
import static org.mapstruct.ReportingPolicy.ERROR;

import com.ticketing.inventory.api.dto.request.ReserveTicketsRequest;
import com.ticketing.inventory.api.dto.response.AvailabilityResponse;
import com.ticketing.inventory.api.dto.response.ReservationResponse;
import com.ticketing.inventory.api.dto.response.TicketPageResponse;
import com.ticketing.inventory.api.dto.response.TicketResponse;
import com.ticketing.inventory.model.Availability;
import com.ticketing.inventory.model.Reservation;
import com.ticketing.inventory.model.ReservationIntent;
import com.ticketing.inventory.model.Ticket;
import com.ticketing.inventory.model.TicketPage;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = SPRING, unmappedTargetPolicy = ERROR)
public interface HttpMapper {
    @Mapping(target = "orderId", source = "orderId")
    ReservationIntent toIntent(ReserveTicketsRequest body, String orderId);

    ReservationResponse toReservationResponse(Reservation reservation);

    AvailabilityResponse toAvailabilityResponse(Availability availability);

    TicketResponse toTicketResponse(Ticket ticket);

    TicketPageResponse toTicketPageResponse(TicketPage page);
}
