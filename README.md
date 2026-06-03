# Event Ledger — Two-Microservice System

A small, production-grade event ledger split into two independent Spring Boot services
that communicate over synchronous REST. The gateway accepts financial events
idempotently and forwards them to an account service that computes balances; the call
is wrapped in a circuit breaker, traced end-to-end, and logged as structured JSON.

---

## 1. Architecture

```
                 POST /events
                 GET  /events/{id}
                 GET  /events?account=
   ┌────────┐    GET  /health            ┌──────────────────┐
   │ client │ ─────────────────────────► │  event-gateway   │  :8080
   └────────┘                            │  (public-facing) │
                                         │                  │
                                         │  H2 (gatewaydb)  │
                                         └────────┬─────────┘
                                                  │ RestClient (synchronous)
                                                  │ Circuit Breaker + Retry + 3s timeout
                                                  │ B3 trace headers (X-B3-TraceId, …)
                                                  ▼
                                         ┌──────────────────┐
                                         │  account-service │  :8081
                                         │  (internal only) │
                                         │                  │
                                         │  H2 (accountdb)  │
                                         └──────────────────┘

   POST /accounts/{id}/transactions
   GET  /accounts/{id}/balance
   GET  /accounts/{id}
   GET  /health
```

- **event-gateway** (port 8080) — public. Ingests events idempotently, persists a
  `LedgerEvent`, and forwards a transaction to account-service.
- **account-service** (port 8081) — internal. Owns `Account` + `AccountTransaction`,
  computes balance as `SUM(CREDIT) - SUM(DEBIT)` via JPQL.
- They share **nothing** — separate H2 databases, separate Spring contexts. Their only
  link is HTTP.

---

## 2. Prerequisites

- **Java 21**
- **Maven 3.8+**
- **Docker + Docker Compose** (only for the containerised run)

---

## 3. Build

```bash
mvn clean package -DskipTests        # build both modules
```

---

## 4. Run with Docker (recommended)

```bash
docker-compose up --build
```

`account-service` starts first; the gateway waits for it to become healthy
(`depends_on: condition: service_healthy`) before starting. The gateway is configured
with `ACCOUNT_SERVICE_URL=http://account-service:8081` via the compose network.

- Gateway:  http://localhost:8080
- Account:  http://localhost:8081

---

## 5. Run manually (two terminals)

```bash
# Terminal 1 — account-service (start this first)
mvn -pl account-service spring-boot:run

# Terminal 2 — event-gateway
mvn -pl event-gateway spring-boot:run
```

By default the gateway calls `http://localhost:8081`. Override with:

```bash
ACCOUNT_SERVICE_URL=http://localhost:8081 mvn -pl event-gateway spring-boot:run
```

---

## 6. Run the tests

```bash
mvn test                       # both modules (11 tests)
mvn -pl account-service test   # account-service only (5)
mvn -pl event-gateway  test    # gateway only (6)
```

The gateway tests use **WireMock** to stub account-service (circuit-breaker,
trace-propagation, graceful-degradation scenarios).

---

## 7. Sample curl commands

### Gateway

```bash
# Submit an event (idempotent). 201 on first submit, 200 + X-Idempotency-Status on replay.
curl -i -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
        "eventId": "evt-001",
        "accountId": "acct-123",
        "type": "CREDIT",
        "amount": 150.00,
        "currency": "USD",
        "eventTimestamp": "2026-05-15T14:02:11Z",
        "metadata": {"source": "mainframe-batch"}
      }'

# Fetch one event by primary key (works even if account-service is down)
curl http://localhost:8080/events/1

# All events for an account, ordered by eventTimestamp ASC
curl "http://localhost:8080/events?account=acct-123"

# Gateway health
curl http://localhost:8080/health
```

### Account service

```bash
# Apply a transaction (account auto-created; idempotent on eventId)
curl -i -X POST http://localhost:8081/accounts/acct-123/transactions \
  -H "Content-Type: application/json" \
  -d '{
        "eventId": "evt-001",
        "type": "CREDIT",
        "amount": 150.00,
        "currency": "USD",
        "eventTimestamp": "2026-05-15T14:02:11Z"
      }'

# Net balance
curl http://localhost:8081/accounts/acct-123/balance

# Account detail (balance + transactions sorted by eventTimestamp ASC)
curl http://localhost:8081/accounts/acct-123

# Account-service health
curl http://localhost:8081/health
```

### Metrics

```bash
curl http://localhost:8080/actuator/metrics/events.received.total
curl http://localhost:8080/actuator/metrics/events.duplicate.total
curl http://localhost:8080/actuator/metrics/events.failed.total
curl http://localhost:8080/actuator/metrics/account.service.call.duration
```

---

## 8. Resiliency — why a Circuit Breaker

The gateway depends on a network call to account-service. Without protection, a slow or
failing downstream would cause requests to pile up on blocked threads (cascading
failure). The gateway wraps the call with **Resilience4j**:

| Concern | Setting |
|---|---|
| Circuit breaker | sliding window 5, failure-rate 50%, open 10s, 2 half-open trial calls |
| Timeout | 3s connect/read on the RestClient |
| Retry | 2 retries, 500ms fixed delay — **only on connection errors, never on 4xx** |

When the breaker is **open**, calls fail fast (`CallNotPermittedException`) instead of
hanging. Either way — open circuit, timeout, connection error, or 5xx — the gateway
records the event with `status = FAILED` and returns **503**, so the client gets a clear,
fast answer and **no event is lost** (it is persisted for later inspection/replay).

Crucially, the **read endpoints never touch the breaker**, so `GET /events/{id}` and
`GET /events?account=` keep working even while account-service is completely down.

---

## 9. Tracing — how a trace flows

Both services use **Micrometer Tracing with the Brave bridge** and **B3 propagation**.

```
client → event-gateway
            │  Spring creates a server span; traceId enters the MDC
            │  traceId is stored on the LedgerEvent
            │  RestClient (auto-configured) injects B3 headers:
            │     X-B3-TraceId, X-B3-SpanId, X-B3-Sampled
            ▼
        account-service
            │  Brave reads the incoming B3 headers and CONTINUES the same trace
            │  the same traceId appears in account-service's JSON logs
```

Because the gateway's RestClient is built from the **auto-configured
`RestClient.Builder`**, the trace context is propagated automatically — no manual header
plumbing. The `traceId` is also persisted on each `LedgerEvent`, so a stored event can
be correlated back to the exact request that produced it.

### Structured logging

Every log line is JSON (via `logstash-logback-encoder`), e.g.:

```json
{"timestamp":"2026-05-15T14:02:11.123Z","level":"INFO","service":"event-gateway","traceId":"a1b2c3d4...","message":"Event ACCEPTED: eventId=evt-001"}
```

The `traceId`/`spanId` come from the MDC (populated by Micrometer Tracing), so a single
trace can be grepped across **both** services' logs.

---

## 10. Tech stack

Java 21 · Spring Boot 3.3 · Maven (multi-module) · Spring Data JPA · H2 (one per service)
· Jakarta Bean Validation · RFC 9457 `ProblemDetail` · Resilience4j · Micrometer Tracing
+ Brave · logstash-logback-encoder · JUnit 5 · MockMvc · WireMock · Docker Compose.

See [`TODO.md`](./TODO.md) for planned enhancements.
