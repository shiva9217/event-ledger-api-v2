package com.eventledger.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Validation rejections happen at binding time, before any account-service call,
 * so no WireMock stub is needed.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ValidationTest {

    @Autowired MockMvc mvc;

    private void expectBadRequest(String body) throws Exception {
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingEventId_returns400() throws Exception {
        expectBadRequest("""
            {"accountId":"a1","type":"CREDIT","amount":10.00,"currency":"USD",
             "eventTimestamp":"2026-05-15T10:00:00Z"}""");
    }

    @Test
    void missingAccountId_returns400() throws Exception {
        expectBadRequest("""
            {"eventId":"e1","type":"CREDIT","amount":10.00,"currency":"USD",
             "eventTimestamp":"2026-05-15T10:00:00Z"}""");
    }

    @Test
    void amountZero_returns400() throws Exception {
        expectBadRequest("""
            {"eventId":"e1","accountId":"a1","type":"CREDIT","amount":0,"currency":"USD",
             "eventTimestamp":"2026-05-15T10:00:00Z"}""");
    }

    @Test
    void amountNegative_returns400() throws Exception {
        expectBadRequest("""
            {"eventId":"e1","accountId":"a1","type":"CREDIT","amount":-5,"currency":"USD",
             "eventTimestamp":"2026-05-15T10:00:00Z"}""");
    }

    @Test
    void unknownType_returns400() throws Exception {
        expectBadRequest("""
            {"eventId":"e1","accountId":"a1","type":"UNKNOWN","amount":10.00,"currency":"USD",
             "eventTimestamp":"2026-05-15T10:00:00Z"}""");
    }
}
