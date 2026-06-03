package com.eventledger.gateway;

import com.eventledger.gateway.domain.LedgerEvent;
import com.eventledger.gateway.repository.LedgerEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * account-service is completely unreachable (URL points at a closed port → connection
 * refused). Writes degrade to 503 (event saved FAILED); reads keep working.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GracefulDegradationTest {

    @Autowired MockMvc mvc;
    @Autowired LedgerEventRepository eventRepository;

    @DynamicPropertySource
    static void deadAccountService(DynamicPropertyRegistry registry) {
        // Nothing listens here → immediate connection refused.
        registry.add("account.service.url", () -> "http://localhost:59999");
    }

    @BeforeEach
    void clean() {
        eventRepository.deleteAll();
    }

    @Test
    void post_returns503_butReadsStillWork() throws Exception {
        String body = """
                {"eventId":"evt-down","accountId":"acct-down","type":"CREDIT","amount":10.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}""";

        // POST → 503 (account-service down), but the event is still recorded as FAILED
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable());

        LedgerEvent saved = eventRepository.findByEventId("evt-down").orElseThrow();

        // GET by id → 200 (unaffected by account-service being down)
        mvc.perform(get("/events/{id}", saved.getId()))
                .andExpect(status().isOk());

        // GET by account → 200
        mvc.perform(get("/events").param("account", "acct-down"))
                .andExpect(status().isOk());
    }
}
