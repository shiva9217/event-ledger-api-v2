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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
public class EventService {

    private final LedgerEventRepository repository;
    private final AccountServiceClient accountServiceClient;
    private final MeterRegistry meterRegistry;
    private final Tracer tracer;
    private final TransactionTemplate transactionTemplate;

    /** Per-eventId locks serialise concurrent submissions of the SAME event (idempotency). */
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public EventService(LedgerEventRepository repository,
                        AccountServiceClient accountServiceClient,
                        MeterRegistry meterRegistry,
                        Tracer tracer,
                        PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.accountServiceClient = accountServiceClient;
        this.meterRegistry = meterRegistry;
        this.tracer = tracer;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
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
    public CreateEventResult ingest(EventRequest request) {
        String traceId = currentTraceId();
        log.info("Ingesting event: eventId={}, accountId={}, type={}, amount={}, traceId={}",
                request.eventId(), request.accountId(), request.type(), request.amount(), traceId);

        // A per-eventId lock serialises concurrent submissions of the same event; the commit
        // happens INSIDE the lock (via TransactionTemplate) so a waiting thread always sees the
        // committed event and takes the idempotent DUPLICATE path - no duplicate row, no 5xx.
        ReentrantLock lock = acquire(request.eventId());
        try {
            return transactionTemplate.execute(status -> doIngest(request, traceId));
        } finally {
            release(request.eventId(), lock);
        }
    }

    private CreateEventResult doIngest(EventRequest request, String traceId) {
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

    private ReentrantLock acquire(String eventId) {
        ReentrantLock lock = locks.computeIfAbsent(eventId, k -> new ReentrantLock());
        lock.lock();
        return lock;
    }

    private void release(String eventId, ReentrantLock lock) {
        lock.unlock();
        locks.remove(eventId);
    }

    private String currentTraceId() {
        var span = tracer.currentSpan();
        return span != null ? span.context().traceId() : null;
    }

    private String metadataAsString(JsonNode metadata) {
        return (metadata == null || metadata.isNull()) ? null : metadata.toString();
    }
}
