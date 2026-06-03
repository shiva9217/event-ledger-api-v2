package com.eventledger.gateway.support;

import com.eventledger.gateway.repository.LedgerEventRepository;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Shared base for gateway tests that stub account-service with WireMock.
 * The account-service URL is wired to the WireMock port via {@link DynamicPropertySource}.
 * Each test starts from a clean slate: WireMock stubs, circuit-breaker state, and the
 * event table are all reset in {@link #resetState()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class WireMockGatewayTest {

    protected static final WireMockServer WIREMOCK =
            new WireMockServer(options().dynamicPort());

    static {
        WIREMOCK.start();
    }

    @DynamicPropertySource
    static void accountServiceProps(DynamicPropertyRegistry registry) {
        registry.add("account.service.url", () -> "http://localhost:" + WIREMOCK.port());
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected LedgerEventRepository eventRepository;
    @Autowired protected CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void resetState() {
        WIREMOCK.resetAll();
        circuitBreakerRegistry.circuitBreaker("accountService").reset();
        eventRepository.deleteAll();
    }

    // NOTE: WIREMOCK is a JVM-wide singleton shared by all test classes (started in the
    // static initializer). It is intentionally NOT stopped per-class — doing so would
    // shut it down for every test class that runs afterwards. The JVM reclaims it on exit.
}
