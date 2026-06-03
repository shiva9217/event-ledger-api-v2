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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AccountAutoCreateTest {

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accountRepository;
    @Autowired AccountTransactionRepository transactionRepository;

    @BeforeEach
    void clean() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void postTransactionForUnknownAccount_createsAccountAutomatically() throws Exception {
        assertThat(accountRepository.existsByAccountId("brand-new")).isFalse();

        String json = """
                {"eventId":"evt-new","type":"CREDIT","amount":75.00,"currency":"USD",
                 "eventTimestamp":"2026-05-15T10:00:00Z"}
                """;

        mvc.perform(post("/accounts/brand-new/transactions")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated());

        // account now exists
        assertThat(accountRepository.existsByAccountId("brand-new")).isTrue();

        // and its balance reflects the transaction
        mvc.perform(get("/accounts/brand-new/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance", is(75.00)));
    }

    @Test
    void getBalanceForUnknownAccount_returns404() throws Exception {
        mvc.perform(get("/accounts/does-not-exist/balance"))
                .andExpect(status().isNotFound());
    }
}
