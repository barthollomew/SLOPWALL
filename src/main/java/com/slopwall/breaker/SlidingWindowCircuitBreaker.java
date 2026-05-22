package com.slopwall.breaker;

import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class SlidingWindowCircuitBreaker implements CircuitBreaker {

    private final int windowSize;
    private final int minCalls;
    private final double failureThreshold;
    private final long openDurationMs;

    private final AtomicIntegerArray window;   // 1=success, 0=failure
    private final AtomicLong head = new AtomicLong(0);
    private final AtomicLong totalRecorded = new AtomicLong(0);
    private final AtomicReference<CircuitBreakerState> state =
        new AtomicReference<>(CircuitBreakerState.CLOSED);
    private volatile long openedAtMs = 0;

    public SlidingWindowCircuitBreaker() {
        this(10, 5, 0.20, 10_000);
    }

    public SlidingWindowCircuitBreaker(int windowSize, int minCalls,
                                        double failureThreshold, long openDurationMs) {
        this.windowSize = windowSize;
        this.minCalls = minCalls;
        this.failureThreshold = failureThreshold;
        this.openDurationMs = openDurationMs;
        this.window = new AtomicIntegerArray(windowSize);
        // initialize all slots as success so fresh circuit doesn't trip on first failure
        for (int i = 0; i < windowSize; i++) window.set(i, 1);
    }

    @Override
    public boolean allowRequest() {
        var s = state.get();
        if (s == CircuitBreakerState.CLOSED) return true;
        if (s == CircuitBreakerState.OPEN) {
            if (System.currentTimeMillis() - openedAtMs >= openDurationMs) {
                state.compareAndSet(CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN);
            }
            return state.get() == CircuitBreakerState.HALF_OPEN;
        }
        return true; // HALF_OPEN: allow the probe request
    }

    @Override
    public void recordSuccess() {
        record(1);
        if (state.get() == CircuitBreakerState.HALF_OPEN) {
            state.set(CircuitBreakerState.CLOSED);
        }
    }

    @Override
    public void recordFailure() {
        record(0);
        if (state.get() == CircuitBreakerState.HALF_OPEN) {
            openedAtMs = System.currentTimeMillis();
            state.set(CircuitBreakerState.OPEN);
            return;
        }
        evaluateAndMaybeTrip();
    }

    @Override
    public CircuitBreakerState state() { return state.get(); }

    private void record(int value) {
        long idx = head.getAndIncrement() % windowSize;
        window.set((int) idx, value);
        totalRecorded.incrementAndGet();
    }

    private void evaluateAndMaybeTrip() {
        if (totalRecorded.get() < minCalls) return;
        int failures = 0;
        for (int i = 0; i < windowSize; i++) {
            if (window.get(i) == 0) failures++;
        }
        if ((double) failures / windowSize > failureThreshold) {
            openedAtMs = System.currentTimeMillis();
            state.compareAndSet(CircuitBreakerState.CLOSED, CircuitBreakerState.OPEN);
        }
    }
}
