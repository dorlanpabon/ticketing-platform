package com.ticketing.orders.api.mapper;

import static org.mapstruct.MappingConstants.ComponentModel.SPRING;
import static org.mapstruct.ReportingPolicy.ERROR;

import com.ticketing.orders.api.dto.response.OrderResponse;
import com.ticketing.orders.model.Order;

import org.mapstruct.Mapper;

@Mapper(componentModel = SPRING, unmappedTargetPolicy = ERROR)
public interface OrderResponseMapper {
    OrderResponse toResponse(Order order);
}
