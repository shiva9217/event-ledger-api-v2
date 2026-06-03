package com.eventledger.gateway.dto;

import com.eventledger.gateway.domain.EventStatus;
import com.eventledger.gateway.domain.EventType;
import com.eventledger.gateway.domain.LedgerEvent;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonRawValue;

import java.math.BigDecimal;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventResponse(
        Long id,
        String eventId,
        String accountId,
        EventType type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp,
        Instant receivedAt,
        EventStatus status,
        @JsonRawValue String metadata,
        String traceId
) {
    public static EventResponse from(LedgerEvent e) {
        return new EventResponse(
                e.getId(),
                e.getEventId(),
                e.getAccountId(),
                e.getType(),
                e.getAmount(),
                e.getCurrency(),
                e.getEventTimestamp(),
                e.getReceivedAt(),
                e.getStatus(),
                e.getMetadata(),
                e.getTraceId());
    }
}
