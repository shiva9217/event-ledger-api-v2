package com.eventledger.gateway.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Builds the RestClient used to call account-service.
 *
 * The bean is created from the auto-configured {@link RestClient.Builder}, which already
 * carries the Micrometer/Brave observation interceptor — that is what injects the
 * B3 trace headers (X-B3-TraceId / X-B3-SpanId) on every outbound call.
 *
 * A 3-second connect/read timeout is applied at the request-factory level; a timeout
 * surfaces as a ResourceAccessException, which the circuit breaker counts as a failure.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient accountServiceRestClient(
            RestClient.Builder builder,
            ObservationRegistry observationRegistry,
            Environment env) {

        String baseUrl = env.getProperty("account.service.url", "http://localhost:8081");

        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(3))
                .withReadTimeout(Duration.ofSeconds(3));

        // Explicitly wiring the ObservationRegistry activates the client observation, whose
        // tracing handler injects the B3 propagation headers on every outbound request.
        return builder
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .observationRegistry(observationRegistry)
                .build();
    }
}
