package com.ticketing.inventory.model;

import java.time.Instant;

public record WorkLease(boolean acquired, Instant until) { }
