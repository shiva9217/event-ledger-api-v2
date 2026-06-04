package com.eventledger.gateway;

import com.eventledger.gateway.repository.LedgerEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gateway read paths and health - none of these call account-service, so no stub is needed.
 * Covers the 404 not-found ProblemDetail and the empty-listing edge case.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GatewayReadTest {

    @Autowired MockMvc mvc;
    @Autowired LedgerEventRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void getByUnknownId_returns404ProblemDetail() throws Exception {
        mvc.perform(get("/events/{id}", 999999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.title", is("Event Not Found")))
                .andExpect(jsonPath("$.service", is("event-gateway")));
    }

    @Test
    void listForAccountWithNoEvents_returnsEmptyArray() throws Exception {
        mvc.perform(get("/events").param("account", "nobody"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void health_returnsUpWithServiceAndDb() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")))
                .andExpect(jsonPath("$.service", is("event-gateway")))
                .andExpect(jsonPath("$.db", is("UP")));
    }
}
