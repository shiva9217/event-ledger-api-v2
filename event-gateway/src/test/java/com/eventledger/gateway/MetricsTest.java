package com.eventledger.gateway;

import com.eventledger.gateway.support.WireMockGatewayTest;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Verifies the custom Micrometer metrics actually move: received (by type), duplicate,
 * failed counters and the account-service call timer.
 */
class MetricsTest extends WireMockGatewayTest {

    @Autowired MeterRegistry meterRegistry;

    private double counter(String name, String... tags) {
        Counter c = meterRegistry.find(name).tags(tags).counter();
        return c == null ? 0d : c.count();
    }

    private long timerCount() {
        Timer t = meterRegistry.find("account.service.call.duration").timer();
        return t == null ? 0L : t.count();
    }

    private void postCredit(String eventId) throws Exception {
        String body = """
                {"eventId":"%s","accountId":"acct-m","type":"CREDIT","amount":10.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}""".formatted(eventId);
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void receivedCounter_andCallTimer_increment_onNewEvent() throws Exception {
        WIREMOCK.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)));

        double received = counter("events.received.total", "type", "CREDIT");
        long timer = timerCount();

        postCredit("m-1");

        assertThat(counter("events.received.total", "type", "CREDIT")).isEqualTo(received + 1);
        assertThat(timerCount()).isGreaterThan(timer);
    }

    @Test
    void duplicateCounter_increments_onReplay() throws Exception {
        WIREMOCK.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)));

        postCredit("m-dup");
        double dup = counter("events.duplicate.total");

        postCredit("m-dup"); // replay

        assertThat(counter("events.duplicate.total")).isEqualTo(dup + 1);
    }

    @Test
    void failedCounter_increments_whenDownstreamFails() throws Exception {
        WIREMOCK.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(500)));

        double failed = counter("events.failed.total");

        postCredit("m-fail");

        assertThat(counter("events.failed.total")).isEqualTo(failed + 1);
    }
}
