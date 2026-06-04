package com.eventledger.account.service;

import com.eventledger.account.domain.Account;
import com.eventledger.account.domain.AccountTransaction;
import com.eventledger.account.dto.AccountDetailResponse;
import com.eventledger.account.dto.ApplyResult;
import com.eventledger.account.dto.BalanceResponse;
import com.eventledger.account.dto.TransactionRequest;
import com.eventledger.account.dto.TransactionResponse;
import com.eventledger.account.exception.AccountNotFoundException;
import com.eventledger.account.repository.AccountRepository;
import com.eventledger.account.repository.AccountTransactionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final AccountTransactionRepository transactionRepository;
    private final TransactionTemplate transactionTemplate;

    /** Per-eventId locks serialise concurrent applies of the SAME event (idempotency). */
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public AccountService(AccountRepository accountRepository,
                          AccountTransactionRepository transactionRepository,
                          PlatformTransactionManager transactionManager) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Applies a transaction to an account.
     * <ul>
     *   <li>Auto-creates the account if it does not exist (upsert).</li>
     *   <li>Idempotent on {@code eventId}: a replay is a no-op that returns the original.</li>
     * </ul>
     */
    public ApplyResult applyTransaction(String accountId, TransactionRequest request) {
        log.info("Applying transaction: accountId={}, eventId={}, type={}, amount={}",
                accountId, request.eventId(), request.type(), request.amount());

        // A per-eventId lock serialises concurrent applies of the same event; the commit
        // happens INSIDE the lock (via TransactionTemplate) so a waiting thread always sees the
        // committed result and takes the idempotent path - no duplicate row, no 5xx.
        ReentrantLock lock = acquire(request.eventId());
        try {
            return transactionTemplate.execute(status -> doApply(accountId, request));
        } finally {
            release(request.eventId(), lock);
        }
    }

    private ApplyResult doApply(String accountId, TransactionRequest request) {
        // Idempotency guard — same eventId is never applied twice.
        var existing = transactionRepository.findByEventId(request.eventId());
        if (existing.isPresent()) {
            log.info("Duplicate transaction ignored: eventId={}", request.eventId());
            return new ApplyResult(toResponse(existing.get()), false);
        }

        Account account = getOrCreateAccount(accountId);

        AccountTransaction txn = AccountTransaction.builder()
                .account(account)
                .eventId(request.eventId())
                .type(request.type())
                .amount(request.amount().setScale(4, RoundingMode.HALF_UP))
                .currency(request.currency())
                .eventTimestamp(request.eventTimestamp())
                .appliedAt(Instant.now())
                .build();

        AccountTransaction saved = transactionRepository.saveAndFlush(txn);
        log.info("Transaction applied: eventId={}, accountId={}", saved.getEventId(), accountId);
        return new ApplyResult(toResponse(saved), true);
    }

    @Transactional(readOnly = true)
    public BalanceResponse getBalance(String accountId) {
        if (!accountRepository.existsByAccountId(accountId)) {
            throw new AccountNotFoundException(accountId);
        }
        BigDecimal balance = nz(transactionRepository.computeBalance(accountId));
        String currency = resolveCurrency(accountId);
        log.info("Balance computed: accountId={}, balance={}, currency={}", accountId, balance, currency);
        return new BalanceResponse(accountId, balance, currency);
    }

    @Transactional(readOnly = true)
    public AccountDetailResponse getAccount(String accountId) {
        if (!accountRepository.existsByAccountId(accountId)) {
            throw new AccountNotFoundException(accountId);
        }
        BigDecimal balance = nz(transactionRepository.computeBalance(accountId));
        List<TransactionResponse> txns =
                transactionRepository.findByAccount_AccountIdOrderByEventTimestampAsc(accountId)
                        .stream().map(this::toResponse).toList();
        return new AccountDetailResponse(accountId, balance, resolveCurrency(accountId), txns);
    }

    public boolean isDatabaseUp() {
        try {
            accountRepository.count();
            return true;
        } catch (Exception ex) {
            log.error("Database health check failed", ex);
            return false;
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private ReentrantLock acquire(String eventId) {
        ReentrantLock lock = locks.computeIfAbsent(eventId, k -> new ReentrantLock());
        lock.lock();
        return lock;
    }

    private void release(String eventId, ReentrantLock lock) {
        lock.unlock();
        locks.remove(eventId);
    }

    private Account getOrCreateAccount(String accountId) {
        return accountRepository.findByAccountId(accountId)
                .orElseGet(() -> {
                    log.info("Auto-creating account: accountId={}", accountId);
                    try {
                        return accountRepository.saveAndFlush(
                                Account.builder().accountId(accountId).createdAt(Instant.now()).build());
                    } catch (DataIntegrityViolationException ex) {
                        // Another thread created it first.
                        return accountRepository.findByAccountId(accountId).orElseThrow(() -> ex);
                    }
                });
    }

    private String resolveCurrency(String accountId) {
        List<String> currencies = transactionRepository.findCurrenciesChronological(accountId);
        return currencies.isEmpty() ? "USD" : currencies.get(0);
    }

    private BigDecimal nz(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    private TransactionResponse toResponse(AccountTransaction t) {
        return new TransactionResponse(
                t.getEventId(),
                t.getAccount().getAccountId(),
                t.getType(),
                t.getAmount().setScale(2, RoundingMode.HALF_UP),
                t.getCurrency(),
                t.getEventTimestamp(),
                t.getAppliedAt());
    }
}
