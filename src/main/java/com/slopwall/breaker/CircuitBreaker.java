package com.slopwall.breaker;

public interface CircuitBreaker {
    boolean allowRequest();
    void recordSuccess();
    void recordFailure();
    CircuitBreakerState state();
}
