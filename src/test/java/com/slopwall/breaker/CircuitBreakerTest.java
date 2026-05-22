package com.slopwall.breaker;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CircuitBreakerTest {

    @Test
    void startsClosedAndAllowsRequests() {
        var cb = new SlidingWindowCircuitBreaker();
        assertTrue(cb.allowRequest());
        assertEquals(CircuitBreakerState.CLOSED, cb.state());
    }

    @Test
    void tripsOpenWhenFailureRateExceeds20Percent() {
        var cb = new SlidingWindowCircuitBreaker();
        // 7 failures in 10 calls = 70% > 20%
        for (int i = 0; i < 3; i++) cb.recordSuccess();
        for (int i = 0; i < 7; i++) cb.recordFailure();
        assertEquals(CircuitBreakerState.OPEN, cb.state());
        assertFalse(cb.allowRequest());
    }

    @Test
    void doesNotTripBelowMinimumCallCount() {
        var cb = new SlidingWindowCircuitBreaker();
        // 4 failures but only 4 total calls (below MIN_CALLS=5)
        for (int i = 0; i < 4; i++) cb.recordFailure();
        assertEquals(CircuitBreakerState.CLOSED, cb.state());
    }

    @Test
    void transitionsToHalfOpenAfterOpenDuration() throws InterruptedException {
        var cb = new SlidingWindowCircuitBreaker(5, 5, 0.20, 100); // 100ms open duration
        for (int i = 0; i < 5; i++) cb.recordFailure();
        assertEquals(CircuitBreakerState.OPEN, cb.state());
        Thread.sleep(150);
        // Next allowRequest() should trigger HALF_OPEN transition
        assertTrue(cb.allowRequest());
        assertEquals(CircuitBreakerState.HALF_OPEN, cb.state());
    }

    @Test
    void halfOpenSuccessClosesCircuit() throws InterruptedException {
        var cb = new SlidingWindowCircuitBreaker(5, 5, 0.20, 100);
        for (int i = 0; i < 5; i++) cb.recordFailure();
        Thread.sleep(150);
        cb.allowRequest(); // trigger HALF_OPEN
        cb.recordSuccess();
        assertEquals(CircuitBreakerState.CLOSED, cb.state());
    }

    @Test
    void halfOpenFailureReopensCircuit() throws InterruptedException {
        var cb = new SlidingWindowCircuitBreaker(5, 5, 0.20, 100);
        for (int i = 0; i < 5; i++) cb.recordFailure();
        Thread.sleep(150);
        cb.allowRequest(); // trigger HALF_OPEN
        cb.recordFailure();
        assertEquals(CircuitBreakerState.OPEN, cb.state());
    }
}
