package com.slopwall.router;

import com.slopwall.breaker.CircuitBreaker;
import com.slopwall.client.AiProvider;
import java.time.Duration;

public record RouterConfig(
    AiProvider primary,
    AiProvider fallback,
    Duration fallbackTimeout,
    CircuitBreaker primaryBreaker,
    CircuitBreaker fallbackBreaker
) {
    public static RouterConfig of(AiProvider primary, AiProvider fallback) {
        return new RouterConfig(primary, fallback, Duration.ofSeconds(2),
            new com.slopwall.breaker.SlidingWindowCircuitBreaker(),
            new com.slopwall.breaker.SlidingWindowCircuitBreaker());
    }
}
