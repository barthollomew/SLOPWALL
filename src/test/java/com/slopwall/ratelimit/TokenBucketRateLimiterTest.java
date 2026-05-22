package com.slopwall.ratelimit;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    @Test
    void consumesUpToCapacity() {
        var limiter = new TokenBucketRateLimiter();
        limiter.registerTenant("t1", 1000, 10);
        for (int i = 0; i < 5; i++) assertTrue(limiter.tryConsume("t1", 200));
        assertFalse(limiter.tryConsume("t1", 1)); // bucket empty
    }

    @Test
    void unknownTenantFailsOpen() {
        var limiter = new TokenBucketRateLimiter();
        assertTrue(limiter.tryConsume("unknown", 999_999)); // fail-open
    }

    @Test
    void reconcileCreditsOverestimate() {
        var limiter = new TokenBucketRateLimiter();
        limiter.registerTenant("t1", 1000, 0);
        limiter.tryConsume("t1", 500);   // estimated 500, actual 300
        limiter.reconcile("t1", 500, 300); // credit back 200
        assertTrue(limiter.tryConsume("t1", 600)); // 1000 - 300 + 200 credited = 700 avail... wait, reconcile adjusts
        // After reconcile: bucket spent 300 actual. 700 available.
        // Consuming 600 should succeed.
    }

    @Test
    void reconcileDebitsUnderestimate() {
        var limiter = new TokenBucketRateLimiter();
        limiter.registerTenant("t1", 1000, 0);
        limiter.tryConsume("t1", 500);    // estimated 500, actual 700
        limiter.reconcile("t1", 500, 700); // debit 200 more
        assertFalse(limiter.tryConsume("t1", 400)); // only 300 available
    }

    @Test
    void concurrentConsumptionNeverExceedsCapacity() throws InterruptedException {
        var limiter = new TokenBucketRateLimiter();
        limiter.registerTenant("shared", 1000, 0);
        var latch = new CountDownLatch(1);
        var succeeded = new AtomicInteger(0);
        var threads = new ArrayList<Thread>(20);
        for (int i = 0; i < 20; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                if (limiter.tryConsume("shared", 100)) succeeded.incrementAndGet();
            }));
        }
        latch.countDown();
        for (var t : threads) t.join();
        assertTrue(succeeded.get() <= 10, "At most 10 calls of 100 tokens fit in 1000-token bucket");
    }
}
