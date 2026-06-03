package com.eventledger.gateway.client;

import com.eventledger.gateway.dto.AccountTransactionRequest;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Synchronous client to account-service, guarded by Resilience4j.
 *
 * Resilience4j aspect order is Retry(outer) → CircuitBreaker(inner). Retry only fires on
 * connection errors (configured via {@code retryExceptions}); HTTP 4xx/5xx are NOT retried.
 * No fallback method is declared on purpose — failures (incl. {@code CallNotPermittedException}
 * when the breaker is open) propagate so the service layer can mark the event FAILED → 503.
 */
@Slf4j
@Component
public class AccountServiceClient {

    private final RestClient restClient;
    private final Timer callTimer;

    public AccountServiceClient(RestClient accountServiceRestClient, MeterRegistry meterRegistry) {
        this.restClient = accountServiceRestClient;
        this.callTimer = Timer.builder("account.service.call.duration")
                .description("Duration of calls to account-service")
                .register(meterRegistry);
    }

    @CircuitBreaker(name = "accountService")
    @Retry(name = "accountService")
    public void applyTransaction(String accountId, AccountTransactionRequest request) {
        callTimer.record(() -> {
            log.info("Calling account-service: POST /accounts/{}/transactions eventId={}",
                    accountId, request.eventId());
            restClient.post()
                    .uri("/accounts/{accountId}/transactions", accountId)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
            log.info("account-service call succeeded for eventId={}", request.eventId());
        });
    }
}
