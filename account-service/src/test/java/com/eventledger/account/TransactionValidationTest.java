package com.eventledger.account;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Negative + error-contract coverage for POST /accounts/{id}/transactions.
 * Asserts both the 400 status and the RFC 9457 ProblemDetail body shape.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TransactionValidationTest {

    @Autowired MockMvc mvc;

    private ResultActions submit(String body) throws Exception {
        return mvc.perform(post("/accounts/acct-x/transactions")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void missingEventId_returns400() throws Exception {
        submit("""
            {"type":"CREDIT","amount":10.00,"currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.service", is("account-service")));
    }

    @Test
    void amountZero_returns400() throws Exception {
        submit("""
            {"eventId":"e","type":"CREDIT","amount":0,"currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("amount")));
    }

    @Test
    void amountNegative_returns400() throws Exception {
        submit("""
            {"eventId":"e","type":"CREDIT","amount":-5,"currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}""")
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingCurrency_returns400() throws Exception {
        submit("""
            {"eventId":"e","type":"CREDIT","amount":10.00,"eventTimestamp":"2026-05-15T10:00:00Z"}""")
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingEventTimestamp_returns400() throws Exception {
        submit("""
            {"eventId":"e","type":"CREDIT","amount":10.00,"currency":"USD"}""")
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownType_returns400() throws Exception {
        submit("""
            {"eventId":"e","type":"TRANSFER","amount":10.00,"currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}""")
                .andExpect(status().isBadRequest());
    }

    @Test
    void malformedJson_returns400() throws Exception {
        submit("{ not valid json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));
    }

    @Test
    void problemDetail_hasRfc9457Shape() throws Exception {
        submit("""
            {"type":"CREDIT","amount":10.00,"currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.title").exists())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.detail").exists())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.service", is("account-service")));
    }
}
