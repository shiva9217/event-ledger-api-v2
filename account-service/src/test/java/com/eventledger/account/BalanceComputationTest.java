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

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BalanceComputationTest {

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accountRepository;
    @Autowired AccountTransactionRepository transactionRepository;

    @BeforeEach
    void clean() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
    }

    private void apply(String eventId, String type, String amount, String ts) throws Exception {
        String json = """
                {"eventId":"%s","type":"%s","amount":%s,"currency":"USD","eventTimestamp":"%s"}
                """.formatted(eventId, type, amount, ts);
        mvc.perform(post("/accounts/acct-bal/transactions")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated());
    }

    @Test
    void mixedCreditsAndDebits_netBalanceIsCorrect() throws Exception {
        // CREDIT 100 + CREDIT 50 + CREDIT 200 - DEBIT 30 - DEBIT 20 = 300
        apply("e1", "CREDIT", "100.00", "2026-05-15T10:00:00Z");
        apply("e2", "CREDIT", "50.00",  "2026-05-15T11:00:00Z");
        apply("e3", "DEBIT",  "30.00",  "2026-05-15T12:00:00Z");
        apply("e4", "CREDIT", "200.00", "2026-05-15T13:00:00Z");
        apply("e5", "DEBIT",  "20.00",  "2026-05-15T14:00:00Z");

        mvc.perform(get("/accounts/acct-bal/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId", is("acct-bal")))
                .andExpect(jsonPath("$.balance", is(300.00)));
    }
}
