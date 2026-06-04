package com.eventledger.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Additional validation cases and the RFC 9457 ProblemDetail error-contract shape
 * for the gateway. These fail at binding/validation time, before any account-service call.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GatewayContractTest {

    @Autowired MockMvc mvc;

    private ResultActions submit(String body) throws Exception {
        return mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void malformedJson_returns400() throws Exception {
        submit("{ broken json ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));
    }

    @Test
    void missingCurrency_returns400() throws Exception {
        submit("""
            {"eventId":"e","accountId":"a","type":"CREDIT","amount":10.00,
             "eventTimestamp":"2026-05-15T10:00:00Z"}""")
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingEventTimestamp_returns400() throws Exception {
        submit("""
            {"eventId":"e","accountId":"a","type":"CREDIT","amount":10.00,"currency":"USD"}""")
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidTimestampFormat_returns400() throws Exception {
        submit("""
            {"eventId":"e","accountId":"a","type":"CREDIT","amount":10.00,"currency":"USD",
             "eventTimestamp":"not-a-date"}""")
                .andExpect(status().isBadRequest());
    }

    @Test
    void validationError_hasRfc9457ProblemDetailShape() throws Exception {
        submit("""
            {"accountId":"a","type":"CREDIT","amount":10.00,"currency":"USD",
             "eventTimestamp":"2026-05-15T10:00:00Z"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.title", is("Validation Failed")))
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.detail").exists())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.service", is("event-gateway")));
    }
}
