package com.eventledger.gateway;

import com.eventledger.gateway.domain.LedgerEvent;
import com.eventledger.gateway.support.WireMockGatewayTest;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TraceIdPropagationTest extends WireMockGatewayTest {

    @Test
    void traceId_isPropagatedViaB3Header_andStoredOnEvent() throws Exception {
        WIREMOCK.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)));

        String body = """
                {"eventId":"evt-trace","accountId":"acct-tr","type":"CREDIT","amount":10.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}""";

        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        // The outbound call carried an X-B3-TraceId header
        List<LoggedRequest> requests =
                WIREMOCK.findAll(postRequestedFor(urlPathMatching("/accounts/.*/transactions")));
        assertThat(requests).hasSize(1);
        String sentTraceId = requests.get(0).getHeader("X-B3-TraceId");
        assertThat(sentTraceId).isNotBlank();

        // The traceId stored on the event matches the one propagated downstream
        LedgerEvent saved = eventRepository.findByEventId("evt-trace").orElseThrow();
        assertThat(saved.getTraceId()).isEqualTo(sentTraceId);
    }
}
