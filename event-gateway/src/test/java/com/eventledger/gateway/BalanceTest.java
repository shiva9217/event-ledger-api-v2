package com.eventledger.gateway;

import com.eventledger.gateway.support.WireMockGatewayTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the gateway forwards each accepted event to account-service. The net of the
 * forwarded transactions (CREDIT 100 + CREDIT 50 - DEBIT 30) is 120 — the value
 * account-service would compute. (Balance computation itself is covered by
 * account-service's BalanceComputationTest.)
 */
class BalanceTest extends WireMockGatewayTest {

    @Autowired ObjectMapper mapper;

    private void submit(String eventId, String type, String amount) throws Exception {
        String body = """
                {"eventId":"%s","accountId":"acct-bal","type":"%s","amount":%s,
                 "currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}"""
                .formatted(eventId, type, amount);
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    @Test
    void creditsAndDebits_forwarded_netIs120() throws Exception {
        WIREMOCK.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)));

        submit("b1", "CREDIT", "100.00");
        submit("b2", "CREDIT", "50.00");
        submit("b3", "DEBIT", "30.00");

        // all 3 forwarded
        List<LoggedRequest> reqs =
                WIREMOCK.findAll(postRequestedFor(urlPathMatching("/accounts/.*/transactions")));
        assertThat(reqs).hasSize(3);

        // net of forwarded transactions = 120
        BigDecimal net = BigDecimal.ZERO;
        for (LoggedRequest r : reqs) {
            JsonNode n = mapper.readTree(r.getBodyAsString());
            BigDecimal amt = n.get("amount").decimalValue();
            net = "CREDIT".equals(n.get("type").asText()) ? net.add(amt) : net.subtract(amt);
        }
        assertThat(net).isEqualByComparingTo("120.00");
    }
}
