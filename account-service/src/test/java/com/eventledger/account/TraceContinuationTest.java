package com.eventledger.account;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 3: the account-service must CONTINUE the trace propagated from the gateway
 * (via B3 headers) and LOG that trace id. Uses a real servlet container (RANDOM_PORT) so the
 * server-side tracing filter actually runs, then asserts the incoming trace id appears in the
 * MDC of the service's log output.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TraceContinuationTest {

    private static final String TRACE_ID = "1234567890abcdef1234567890abcdef";
    private static final String SPAN_ID = "1234567890abcdef";

    @Autowired TestRestTemplate rest;
    @LocalServerPort int port;

    private ListAppender<ILoggingEvent> appender;
    private Logger root;

    @BeforeEach
    void attachAppender() {
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        root.detachAppender(appender);
    }

    @Test
    void incomingB3TraceId_isAdoptedAndLogged() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-B3-TraceId", TRACE_ID);
        headers.add("X-B3-SpanId", SPAN_ID);
        headers.add("X-B3-Sampled", "1");

        String body = """
                {"eventId":"trace-cont","type":"CREDIT","amount":10.00,"currency":"USD",
                 "eventTimestamp":"2026-05-15T10:00:00Z"}""";

        ResponseEntity<String> resp = rest.postForEntity(
                "http://localhost:" + port + "/accounts/acct-trace/transactions",
                new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // A log event emitted while handling the request carries the propagated traceId in its MDC,
        // proving the account-service continued the gateway's trace rather than starting a new one.
        boolean logged = appender.list.stream()
                .anyMatch(e -> TRACE_ID.equals(e.getMDCPropertyMap().get("traceId")));

        assertThat(logged)
                .as("account-service should continue and log the gateway's trace id %s", TRACE_ID)
                .isTrue();
    }
}
