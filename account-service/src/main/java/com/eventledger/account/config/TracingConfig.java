package com.eventledger.account.config;

import brave.propagation.B3Propagation;
import brave.propagation.Propagation;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures Brave to use B3 propagation so the account-service EXTRACTS the trace context
 * propagated by the gateway (X-B3-TraceId / X-B3-SpanId / X-B3-Sampled) and CONTINUES the
 * same trace, rather than starting a fresh one. This is what makes a single client request
 * produce one traceable path across both services.
 */
@Configuration
public class TracingConfig {

    @Bean
    public Propagation.Factory propagationFactory() {
        return B3Propagation.newFactoryBuilder()
                .injectFormat(B3Propagation.Format.MULTI)
                .build();
    }
}
