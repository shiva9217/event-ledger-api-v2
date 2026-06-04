package com.eventledger.gateway;

import com.eventledger.gateway.domain.EventStatus;
import com.eventledger.gateway.support.WireMockGatewayTest;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.Fault;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deep resiliency behaviour of the gateway -> account-service call:
 * retry only on connection errors (never on 4xx), timeout -> 503, circuit-breaker
 * half-open recovery, and persistence of status=FAILED on downstream failure.
 */
class ResiliencyTest extends WireMockGatewayTest {

    private void submitFailing(String eventId) throws Exception {
        String body = """
                {"eventId":"%s","accountId":"acct-r","type":"CREDIT","amount":10.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}""".formatted(eventId);
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void connectionError_isRetried_threeAttemptsTotal() throws Exception {
        // A connection reset is a transient/connection error -> retried (1 initial + 2 retries).
        WIREMOCK.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        submitFailing("evt-retry");

        WIREMOCK.verify(3, postRequestedFor(urlPathMatching("/accounts/.*/transactions")));
        // event is still recorded, with status FAILED
        assertThat(eventRepository.findByEventId("evt-retry").orElseThrow().getStatus())
                .isEqualTo(EventStatus.FAILED);
    }

    @Test
    void clientError4xx_isNotRetried_singleAttempt() throws Exception {
        // A 4xx is a deterministic error -> NOT retried.
        WIREMOCK.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(400)));

        submitFailing("evt-4xx");

        WIREMOCK.verify(1, postRequestedFor(urlPathMatching("/accounts/.*/transactions")));
        assertThat(eventRepository.findByEventId("evt-4xx").orElseThrow().getStatus())
                .isEqualTo(EventStatus.FAILED);
    }

    @Test
    void slowDownstreamBeyondTimeout_returns503() throws Exception {
        // Fixed delay (4s) exceeds the 3s read timeout -> ResourceAccessException -> 503.
        WIREMOCK.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201).withFixedDelay(4000)));

        submitFailing("evt-timeout");

        assertThat(eventRepository.findByEventId("evt-timeout").orElseThrow().getStatus())
                .isEqualTo(EventStatus.FAILED);
    }

    @Test
    void circuitBreaker_recoversFromOpenToClosed_viaHalfOpen() throws Exception {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("accountService");

        // 1) Drive it OPEN with 5 downstream failures (sliding window = 5).
        WIREMOCK.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(500)));
        for (int i = 1; i <= 5; i++) {
            submitFailing("evt-open-" + i);
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // 2) Move to HALF_OPEN and make the downstream healthy again.
        cb.transitionToHalfOpenState();
        WIREMOCK.resetAll();
        WIREMOCK.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)));

        // 3) The permitted half-open trial calls (2) succeed -> breaker CLOSES.
        for (int i = 1; i <= 2; i++) {
            String body = """
                    {"eventId":"evt-recover-%d","accountId":"acct-r","type":"CREDIT","amount":5.00,
                     "currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}""".formatted(i);
            mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated());
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
