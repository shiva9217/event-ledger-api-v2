package com.eventledger.gateway.dto;

import com.eventledger.gateway.domain.EventType;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Public event submission payload.
 * {@code type} is an enum so an unknown value is rejected with 400 at binding time.
 * {@code metadata} accepts any JSON value and is stored verbatim.
 */
public record EventRequest(

        @NotBlank(message = "eventId is required")
        String eventId,

        @NotBlank(message = "accountId is required")
        String accountId,

        @NotNull(message = "type is required and must be CREDIT or DEBIT")
        EventType type,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.0", inclusive = false, message = "amount must be greater than 0")
        BigDecimal amount,

        @NotBlank(message = "currency is required")
        String currency,

        @NotNull(message = "eventTimestamp is required")
        Instant eventTimestamp,

        JsonNode metadata
) {
}
