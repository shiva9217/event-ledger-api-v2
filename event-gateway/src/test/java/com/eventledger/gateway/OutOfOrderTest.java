package com.eventledger.gateway;

import com.eventledger.gateway.support.WireMockGatewayTest;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OutOfOrderTest extends WireMockGatewayTest {

    private void submit(String eventId, String ts) throws Exception {
        String body = """
                {"eventId":"%s","accountId":"acct-ooo","type":"CREDIT","amount":10.00,
                 "currency":"USD","eventTimestamp":"%s"}""".formatted(eventId, ts);
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    @Test
    void eventsReturnedInEventTimestampOrder_regardlessOfArrivalOrder() throws Exception {
        WIREMOCK.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)));

        // Arrival order: T2 (later) first, then T1 (earlier).
        submit("evt-T2", "2026-05-15T20:00:00Z");
        submit("evt-T1", "2026-05-15T08:00:00Z");

        // Listing is ordered by eventTimestamp ASC → T1 first.
        mvc.perform(get("/events").param("account", "acct-ooo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].eventId", contains("evt-T1", "evt-T2")));
    }
}
