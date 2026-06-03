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

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OutOfOrderBalanceTest {

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
        mvc.perform(post("/accounts/acct-ooo/transactions")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated());
    }

    @Test
    void debitAppliedBeforeCredit_balanceAndOrderStillCorrect() throws Exception {
        // Arrival order: DEBIT (later ts) first, then CREDIT (earlier ts).
        apply("d1", "DEBIT",  "40.00", "2026-05-15T18:00:00Z"); // arrives first
        apply("c1", "CREDIT", "100.00", "2026-05-15T09:00:00Z"); // arrives second, earlier ts

        // Net balance is independent of arrival order: 100 - 40 = 60
        mvc.perform(get("/accounts/acct-ooo/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance", is(60.00)));

        // Account detail lists transactions by eventTimestamp ASC → credit (09:00) first
        mvc.perform(get("/accounts/acct-ooo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions[*].eventId", contains("c1", "d1")));
    }
}
