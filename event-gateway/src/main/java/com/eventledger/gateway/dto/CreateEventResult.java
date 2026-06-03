package com.eventledger.gateway.dto;

import com.eventledger.gateway.domain.EventStatus;

/**
 * Outcome of POST /events, used by the controller to choose the HTTP status:
 *   ACCEPTED  → 201
 *   DUPLICATE → 200 (+ X-Idempotency-Status: DUPLICATE)
 *   FAILED    → 503
 */
public record CreateEventResult(EventResponse event, EventStatus outcome) {
}
