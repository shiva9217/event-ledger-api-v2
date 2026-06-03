package com.eventledger.gateway.service;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.domain.EventStatus;
import com.eventledger.gateway.domain.LedgerEvent;
import com.eventledger.gateway.dto.AccountTransactionRequest;
import com.eventledger.gateway.dto.CreateEventResult;
import com.eventledger.gateway.dto.EventRequest;
import com.eventledger.gateway.dto.EventResponse;
import com.eventledger.gateway.exception.EventNotFoundException;
import com.eventledger.gateway.repository.LedgerEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

@Slf4j
@Service
public class EventService {

    private final LedgerEventRepository repository;
    private final AccountServiceClient accountServiceClient;
    private final MeterRegistry meterRegistry;
    private final Tracer tracer;

    public EventService(LedgerEventRepository repository,
                        AccountServiceClient accountServiceClient,
                        MeterRegistry meterRegistry,
                        Tracer tracer) {
        this.repository = repository;
        this.accountServiceClient = accountServiceClient;
        this.meterRegistry = meterRegistry;
        this.tracer = tracer;
    }

    /**
     * Ingest an event.
     * <ul>
     *   <li>DUPLICATE eventId → return original (HTTP 200, no side effects).</li>
     *   <li>NEW → persist, call account-service:
     *       <ul>
     *         <li>success → status ACCEPTED (201)</li>
     *         <li>account-service unreachable → status FAILED (503), event still saved</li>
     *       </ul>
     *   </li>
     * </ul>
     */
    @Transactional
    public CreateEventResult ingest(EventRequest request) {
        String traceId = currentTraceId();
        log.info("Ingesting event: eventId={}, accountId={}, type={}, amount={}, traceId={}",
                request.eventId(), request.accountId(), request.type(), request.amount(), traceId);

        // Idempotency — return the original, untouched.
        var existing = repository.findByEventId(request.eventId());
        if (existing.isPresent()) {
            meterRegistry.counter("events.duplicate.total").increment();
            log.info("Duplicate event: eventId={} — returning original", request.eventId());
            return new CreateEventResult(EventResponse.from(existing.get()), EventStatus.DUPLICATE);
        }

        meterRegistry.counter("events.received.total", "type", request.type().name()).increment();

        LedgerEvent event = LedgerEvent.builder()
                .eventId(request.eventId())
                .accountId(request.accountId())
                .type(request.type())
                .amount(request.amount().setScale(4, RoundingMode.HALF_UP))
                .currency(request.currency())
                .eventTimestamp(request.eventTimestamp())
                .receivedAt(Instant.now())
                .metadata(metadataAsString(request.metadata()))
                .traceId(traceId)
                .build();

        try {
            accountServiceClient.applyTransaction(request.accountId(),
                    new AccountTransactionRequest(
                            request.eventId(), request.type(), request.amount(),
                            request.currency(), request.eventTimestamp()));
            event.setStatus(EventStatus.ACCEPTED);
            LedgerEvent saved = repository.save(event);
            log.info("Event ACCEPTED: eventId={}", saved.getEventId());
            return new CreateEventResult(EventResponse.from(saved), EventStatus.ACCEPTED);

        } catch (Exception ex) {
            // Circuit open, timeout, connection error, or 5xx — degrade gracefully.
            meterRegistry.counter("events.failed.total").increment();
            event.setStatus(EventStatus.FAILED);
            LedgerEvent saved = repository.save(event);
            log.error("Event FAILED (account-service unreachable): eventId={}, reason={}",
                    saved.getEventId(), ex.getMessage());
            return new CreateEventResult(EventResponse.from(saved), EventStatus.FAILED);
        }
    }

    @Transactional(readOnly = true)
    public EventResponse getById(Long id) {
        LedgerEvent event = repository.findById(id)
                .orElseThrow(() -> new EventNotFoundException(id));
        return EventResponse.from(event);
    }

    @Transactional(readOnly = true)
    public List<EventResponse> getByAccount(String accountId) {
        return repository.findByAccountIdOrderByEventTimestampAsc(accountId)
                .stream().map(EventResponse::from).toList();
    }

    public boolean isDatabaseUp() {
        try {
            repository.count();
            return true;
        } catch (Exception ex) {
            log.error("Database health check failed", ex);
            return false;
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private String currentTraceId() {
        var span = tracer.currentSpan();
        return span != null ? span.context().traceId() : null;
    }

    private String metadataAsString(JsonNode metadata) {
        return (metadata == null || metadata.isNull()) ? null : metadata.toString();
    }
}
