package com.eventledger.account;

import com.eventledger.account.repository.AccountRepository;
import com.eventledger.account.repository.AccountTransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TransactionIdempotencyTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AccountRepository accountRepository;
    @Autowired AccountTransactionRepository transactionRepository;

    @BeforeEach
    void clean() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
    }

    private String body(String eventId) {
        return """
               {"eventId":"%s","type":"CREDIT","amount":100.00,"currency":"USD",
                "eventTimestamp":"2026-05-15T10:00:00Z"}
               """.formatted(eventId);
    }

    @Test
    void sameEventIdTwice_secondIsNoOp_balanceUnchanged() throws Exception {
        // first apply → 201
        mvc.perform(post("/accounts/acct-1/transactions")
                        .contentType(MediaType.APPLICATION_JSON).content(body("evt-1")))
                .andExpect(status().isCreated());

        // replay same eventId → 200 (idempotent)
        mvc.perform(post("/accounts/acct-1/transactions")
                        .contentType(MediaType.APPLICATION_JSON).content(body("evt-1")))
                .andExpect(status().isOk());

        // balance reflects a single CREDIT of 100, not 200
        mvc.perform(get("/accounts/acct-1/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance", is(100.00)));

        // exactly one transaction stored
        org.assertj.core.api.Assertions.assertThat(transactionRepository.count()).isEqualTo(1);
    }
}
