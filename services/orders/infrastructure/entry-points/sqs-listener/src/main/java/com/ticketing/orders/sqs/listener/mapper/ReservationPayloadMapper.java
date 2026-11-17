package com.ticketing.orders.sqs.listener.mapper;

import static org.mapstruct.MappingConstants.ComponentModel.SPRING;
import static org.mapstruct.ReportingPolicy.ERROR;

import com.ticketing.orders.model.Reservation;
import com.ticketing.orders.sqs.listener.dto.ReservationStatePayload;

import org.mapstruct.Mapper;

@Mapper(componentModel = SPRING, unmappedTargetPolicy = ERROR)
public interface ReservationPayloadMapper {
    Reservation toDomain(ReservationStatePayload payload);
}
