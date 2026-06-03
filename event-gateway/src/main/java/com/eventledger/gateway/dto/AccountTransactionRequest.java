package com.eventledger.gateway.dto;

import com.eventledger.gateway.domain.EventType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Payload the gateway sends to account-service
 * (POST /accounts/{accountId}/transactions).
 */
public record AccountTransactionRequest(
        String eventId,
        EventType type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp
) {
}
