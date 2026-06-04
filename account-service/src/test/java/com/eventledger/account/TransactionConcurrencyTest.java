package com.eventledger.account;

import com.eventledger.account.repository.AccountRepository;
import com.eventledger.account.repository.AccountTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * Concurrency: many simultaneous applies of the SAME eventId must apply exactly once
 * (no duplicate transaction, no double balance) and never surface a 5xx.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TransactionConcurrencyTest {

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accountRepository;
    @Autowired AccountTransactionRepository transactionRepository;

    @BeforeEach
    void clean() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void simultaneousSameEventId_appliedExactlyOnce() throws Exception {
        int threads = 12;
        String body = """
                {"eventId":"race-1","type":"CREDIT","amount":100.00,"currency":"USD",
                 "eventTimestamp":"2026-05-15T10:00:00Z"}""";

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                ready.countDown();
                go.await();
                return mvc.perform(post("/accounts/acct-race/transactions")
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

        // No server errors; every response is a 2xx (201 once, 200 for the idempotent rest).
        assertThat(statuses).allSatisfy(s -> assertThat(s).isBetween(200, 299));
        assertThat(statuses).contains(201);

        // Exactly one transaction stored, balance applied once.
        assertThat(transactionRepository.count()).isEqualTo(1);
        mvc.perform(get("/accounts/acct-race/balance"))
                .andExpect(jsonPath("$.balance").value(100.00));
    }
}
