package com.eventledger.account.repository;

import com.eventledger.account.domain.AccountTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface AccountTransactionRepository extends JpaRepository<AccountTransaction, Long> {

    Optional<AccountTransaction> findByEventId(String eventId);

    boolean existsByEventId(String eventId);

    List<AccountTransaction> findByAccount_AccountIdOrderByEventTimestampAsc(String accountId);

    /**
     * Net balance computed directly in the database:
     *   SUM(CREDIT) - SUM(DEBIT)
     * COALESCE keeps it zero-safe when a side has no rows.
     * Order of arrival is irrelevant — this is a pure aggregate.
     */
    @Query("""
           SELECT COALESCE(SUM(CASE WHEN t.type = com.eventledger.account.domain.TransactionType.CREDIT
                                    THEN t.amount ELSE 0 END), 0)
                - COALESCE(SUM(CASE WHEN t.type = com.eventledger.account.domain.TransactionType.DEBIT
                                    THEN t.amount ELSE 0 END), 0)
           FROM AccountTransaction t
           WHERE t.account.accountId = :accountId
           """)
    BigDecimal computeBalance(@Param("accountId") String accountId);

    /** Currency of the earliest transaction (chronologically); used for the balance response. */
    @Query("""
           SELECT t.currency FROM AccountTransaction t
           WHERE t.account.accountId = :accountId
           ORDER BY t.eventTimestamp ASC
           """)
    List<String> findCurrenciesChronological(@Param("accountId") String accountId);
}
