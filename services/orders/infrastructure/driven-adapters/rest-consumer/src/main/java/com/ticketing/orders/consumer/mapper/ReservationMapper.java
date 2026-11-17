package com.ticketing.orders.consumer.mapper;

import static org.mapstruct.MappingConstants.ComponentModel.SPRING;
import static org.mapstruct.ReportingPolicy.ERROR;

import com.ticketing.orders.consumer.dto.request.ReservationRequest;
import com.ticketing.orders.consumer.dto.response.ReservationResponse;
import com.ticketing.orders.model.Order;
import com.ticketing.orders.model.Reservation;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = SPRING, unmappedTargetPolicy = ERROR)
public interface ReservationMapper {
    @Mapping(target = "ticketIds", source = "requestedTicketIds")
    ReservationRequest toRequest(Order order);

    Reservation toDomain(ReservationResponse response);
}
