package com.ticketing.inventory.sqs.listener.mapper;

import static org.mapstruct.MappingConstants.ComponentModel.SPRING;
import static org.mapstruct.ReportingPolicy.ERROR;

import com.ticketing.inventory.model.ReservationIntent;
import com.ticketing.inventory.sqs.listener.dto.ReservationPayload;

import org.mapstruct.Mapper;

@Mapper(componentModel = SPRING, unmappedTargetPolicy = ERROR)
public interface ReservationCommandMapper {
    ReservationIntent toIntent(ReservationPayload payload);
}
