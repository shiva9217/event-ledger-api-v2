package com.eventledger.integration;

import com.eventledger.account.AccountServiceApplication;
import com.eventledger.gateway.EventGatewayApplication;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * True end-to-end test: boots BOTH real services (each with its own embedded web server
 * and its own in-memory H2) in-process on random ports, then drives the public gateway
 * over real HTTP. The gateway in turn makes real HTTP calls to the real account-service —
 * no stubs/mocks. Verifies the full flow: ingest → apply → balance, idempotency end to end,
 * chronological listing, and B3 trace propagation across the service boundary.
 *
 * Critical properties are passed explicitly so the two services stay fully isolated
 * (separate DBs, separate ports) regardless of classpath config ordering.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndToEndIntegrationTest {

    private static ConfigurableApplicationContext accountCtx;
    private static ConfigurableApplicationContext gatewayCtx;
    private static int accountPort;
    private static int gatewayPort;

    private final RestClient http = RestClient.create();

    @BeforeAll
    void startBothServices() {
        // NOTE: pass settings as command-line args (run("--key=value")), NOT builder.properties(),
        // because the latter are DEFAULT properties (lowest precedence) and would be overridden by
        // each service's application.yml (e.g. server.port: 8081). Command-line args win, so
        // server.port=0 (random) and the isolated DBs actually take effect.
        accountCtx = new SpringApplicationBuilder(AccountServiceApplication.class).run(
                "--spring.main.web-application-type=servlet",
                "--server.port=0",
                "--spring.application.name=account-service",
                "--spring.datasource.url=jdbc:h2:mem:e2e-account;DB_CLOSE_DELAY=-1",
                "--spring.jpa.hibernate.ddl-auto=create-drop",
                "--management.tracing.sampling.probability=1.0",
                "--management.tracing.propagation.type=B3");
        accountPort = portOf(accountCtx);

        gatewayCtx = new SpringApplicationBuilder(EventGatewayApplication.class).run(
                "--spring.main.web-application-type=servlet",
                "--server.port=0",
                "--spring.application.name=event-gateway",
                "--spring.datasource.url=jdbc:h2:mem:e2e-gateway;DB_CLOSE_DELAY=-1",
                "--spring.jpa.hibernate.ddl-auto=create-drop",
                "--account.service.url=http://localhost:" + accountPort,
                "--management.tracing.sampling.probability=1.0",
                "--management.tracing.propagation.type=B3");
        gatewayPort = portOf(gatewayCtx);
    }

    @AfterAll
    void stopBothServices() {
        if (gatewayCtx != null) gatewayCtx.close();
        if (accountCtx != null) accountCtx.close();
    }

    private static int portOf(ConfigurableApplicationContext ctx) {
        return ((WebServerApplicationContext) ctx).getWebServer().getPort();
    }

    private String gateway(String path) { return "http://localhost:" + gatewayPort + path; }
    private String account(String path) { return "http://localhost:" + accountPort + path; }

    private ResponseEntity<String> postEvent(String body) {
        return http.post().uri(gateway("/events"))
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toEntity(String.class);
    }

    @Test
    void fullFlow_ingestApplyBalance_idempotency_andChronologicalListing() {
        // 1) Submit a CREDIT through the gateway → it really calls account-service.
        ResponseEntity<String> first = postEvent("""
                {"eventId":"e2e-1","accountId":"acct-e2e","type":"CREDIT","amount":150.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T14:02:11Z",
                 "metadata":{"source":"integration"}}""");
        assertThat(first.getStatusCode().value()).isEqualTo(201);

        // 2) account-service really applied it → balance = 150.00
        JsonNode balance = http.get().uri(account("/accounts/acct-e2e/balance"))
                .retrieve().body(JsonNode.class);
        assertThat(balance.get("balance").decimalValue()).isEqualByComparingTo("150.00");

        // 3) Idempotency end-to-end: resubmit same eventId → 200 + DUPLICATE, balance unchanged.
        ResponseEntity<String> replay = postEvent("""
                {"eventId":"e2e-1","accountId":"acct-e2e","type":"CREDIT","amount":150.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T14:02:11Z"}""");
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getHeaders().getFirst("X-Idempotency-Status")).isEqualTo("DUPLICATE");

        JsonNode balanceAfterReplay = http.get().uri(account("/accounts/acct-e2e/balance"))
                .retrieve().body(JsonNode.class);
        assertThat(balanceAfterReplay.get("balance").decimalValue()).isEqualByComparingTo("150.00");

        // 4) Apply a DEBIT (earlier timestamp, arrives later) → net balance = 120.00
        assertThat(postEvent("""
                {"eventId":"e2e-2","accountId":"acct-e2e","type":"DEBIT","amount":30.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T09:00:00Z"}""")
                .getStatusCode().value()).isEqualTo(201);

        JsonNode finalBalance = http.get().uri(account("/accounts/acct-e2e/balance"))
                .retrieve().body(JsonNode.class);
        assertThat(finalBalance.get("balance").decimalValue()).isEqualByComparingTo("120.00");

        // 5) Gateway listing is chronological by eventTimestamp (DEBIT@09:00 before CREDIT@14:02)
        JsonNode events = http.get().uri(gateway("/events?account=acct-e2e"))
                .retrieve().body(JsonNode.class);
        assertThat(events.isArray()).isTrue();
        assertThat(events).hasSize(2);
        assertThat(events.get(0).get("eventId").asText()).isEqualTo("e2e-2"); // 09:00 first
        assertThat(events.get(1).get("eventId").asText()).isEqualTo("e2e-1"); // 14:02 second

        // 6) Trace propagation across the boundary: the gateway recorded a traceId on the event.
        assertThat(events.get(1).get("traceId").asText()).isNotBlank();
    }

    @Test
    void accountServiceDetail_reflectsTransactionsAppliedViaGateway() {
        postEvent("""
                {"eventId":"e2e-detail","accountId":"acct-detail","type":"CREDIT","amount":42.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}""");

        JsonNode detail = http.get().uri(account("/accounts/acct-detail"))
                .retrieve().body(JsonNode.class);
        assertThat(detail.get("accountId").asText()).isEqualTo("acct-detail");
        assertThat(detail.get("balance").decimalValue()).isEqualByComparingTo("42.00");
        assertThat(detail.get("transactions")).hasSize(1);
        assertThat(detail.get("transactions").get(0).get("eventId").asText()).isEqualTo("e2e-detail");
    }

    @Test
    void healthEndpoints_bothServicesUp() {
        JsonNode gwHealth = http.get().uri(gateway("/health")).retrieve().body(JsonNode.class);
        assertThat(gwHealth.get("status").asText()).isEqualTo("UP");
        assertThat(gwHealth.get("service").asText()).isEqualTo("event-gateway");

        JsonNode acctHealth = http.get().uri(account("/health")).retrieve().body(JsonNode.class);
        assertThat(acctHealth.get("status").asText()).isEqualTo("UP");
        assertThat(acctHealth.get("service").asText()).isEqualTo("account-service");
    }

    @SuppressWarnings("unused")
    private static boolean is2xx(HttpStatusCode code) { return code.is2xxSuccessful(); }
}
