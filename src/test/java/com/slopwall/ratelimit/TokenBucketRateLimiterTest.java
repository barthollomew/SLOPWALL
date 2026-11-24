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
        assertFalse(limiter.tryConsume("t1", 1));
    }

    @Test
    void unknownTenantFailsOpen() {
        var limiter = new TokenBucketRateLimiter();
        assertTrue(limiter.tryConsume("unknown", 999_999));
    }

    @Test
    void reconcileCreditsOverestimate() {
        var limiter = new TokenBucketRateLimiter();
        limiter.registerTenant("t1", 1000, 0);
        limiter.tryConsume("t1", 500);
        limiter.reconcile("t1", 500, 300);
        assertTrue(limiter.tryConsume("t1", 600));
    }

    @Test
    void reconcileDebitsUnderestimate() {
        var limiter = new TokenBucketRateLimiter();
        limiter.registerTenant("t1", 1000, 0);
        limiter.tryConsume("t1", 500);
        limiter.reconcile("t1", 500, 700);
        assertFalse(limiter.tryConsume("t1", 400));
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
