package com.eventledger.gateway;

import com.eventledger.gateway.domain.LedgerEvent;
import com.eventledger.gateway.support.WireMockGatewayTest;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CircuitBreakerTest extends WireMockGatewayTest {

    private void submit(String eventId) throws Exception {
        String body = """
                {"eventId":"%s","accountId":"acct-cb","type":"CREDIT","amount":10.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}""".formatted(eventId);
        // every POST fails downstream → gateway records FAILED and returns 503
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void accountServiceFailing_circuitOpens_gatewayReturns503_andReadsStillWork() throws Exception {
        // account-service returns 500 for every call
        WIREMOCK.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(500)));

        // sliding window = 5 → 5 failing calls fill it and open the breaker
        for (int i = 1; i <= 5; i++) {
            submit("evt-cb-" + i);
        }

        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("accountService");
        assertThat(cb.getState()).isIn(CircuitBreaker.State.OPEN, CircuitBreaker.State.FORCED_OPEN);

        // a further call is short-circuited (CallNotPermitted) and still returns 503
        submit("evt-cb-6");

        // READ paths are unaffected by the open circuit
        LedgerEvent any = eventRepository.findByEventId("evt-cb-1").orElseThrow();
        mvc.perform(get("/events/{id}", any.getId()))
                .andExpect(status().isOk());

        mvc.perform(get("/events").param("account", "acct-cb"))
                .andExpect(status().isOk());
    }
}
