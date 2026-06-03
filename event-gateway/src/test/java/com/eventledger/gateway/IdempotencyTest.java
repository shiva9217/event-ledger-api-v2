package com.eventledger.gateway;

import com.eventledger.gateway.support.WireMockGatewayTest;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IdempotencyTest extends WireMockGatewayTest {

    private static final String BODY = """
            {"eventId":"evt-1","accountId":"acct-1","type":"CREDIT","amount":100.00,
             "currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}""";

    @Test
    void samePost_twice_secondReturns200AndDuplicateHeader_accountCalledOnce() throws Exception {
        WIREMOCK.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)));

        // first → 201 ACCEPTED
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());

        // second (same eventId) → 200 + X-Idempotency-Status: DUPLICATE
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Idempotency-Status", "DUPLICATE"));

        // account-service was called exactly once — the duplicate did NOT forward
        WIREMOCK.verify(1, postRequestedFor(urlPathMatching("/accounts/.*/transactions")));
    }
}
