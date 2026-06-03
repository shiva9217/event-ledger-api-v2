package com.eventledger.gateway.config;

import brave.propagation.B3Propagation;
import brave.propagation.Propagation;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Forces Brave to inject B3 trace context using the MULTI header format
 * (X-B3-TraceId / X-B3-SpanId / X-B3-Sampled) rather than the single {@code b3} header,
 * so downstream services (and tests) can read the discrete X-B3-* headers.
 * Extraction still understands both formats.
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
