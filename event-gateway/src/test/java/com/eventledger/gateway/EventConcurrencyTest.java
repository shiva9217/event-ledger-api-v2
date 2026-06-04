package com.eventledger.gateway;

import com.eventledger.gateway.support.WireMockGatewayTest;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Concurrency: many simultaneous POSTs of the SAME eventId must store exactly one event
 * and never surface a 5xx. Exactly one submission is ACCEPTED (201); the rest are idempotent.
 */
class EventConcurrencyTest extends WireMockGatewayTest {

    @Test
    void simultaneousSameEventId_oneEventStored_noServerError() throws Exception {
        WIREMOCK.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)));

        int threads = 12;
        String body = """
                {"eventId":"race-evt","accountId":"acct-race","type":"CREDIT","amount":100.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}""";

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);

        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                ready.countDown();
                go.await();
                return mvc.perform(post("/events")
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andReturn().getResponse().getStatus();
            });
        }
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> t : tasks) futures.add(pool.submit(t));
        ready.await();
        go.countDown();

        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : futures) statuses.add(f.get());
        pool.shutdown();

        // No 5xx; every response is a 2xx (one 201 ACCEPTED, the rest 200 DUPLICATE).
        assertThat(statuses).allSatisfy(s -> assertThat(s).isBetween(200, 299));
        assertThat(statuses).contains(201);
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);

        // Exactly one event persisted for that eventId.
        assertThat(eventRepository.findByEventId("race-evt")).isPresent();
        assertThat(eventRepository.count()).isEqualTo(1);
    }
}
