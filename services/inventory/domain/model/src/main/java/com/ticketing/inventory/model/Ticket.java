package com.ticketing.inventory.model;

public record Ticket(String eventId, String ticketId, TicketStatus status) {
}
