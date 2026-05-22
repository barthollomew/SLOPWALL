package com.slopwall.ratelimit;

import java.util.concurrent.atomic.AtomicLong;

final class TenantBucket {
    private final long capacity;
    private final long refillRatePerSecond;
    private final AtomicLong available;
    private final AtomicLong lastRefillMs = new AtomicLong(System.currentTimeMillis());

    TenantBucket(long capacity, long refillRatePerSecond) {
        this.capacity = capacity;
        this.refillRatePerSecond = refillRatePerSecond;
        this.available = new AtomicLong(capacity);
    }

    boolean tryConsume(long tokens) {
        refill();
        long cur;
        do {
            cur = available.get();
            if (cur < tokens) return false;
        } while (!available.compareAndSet(cur, cur - tokens));
        return true;
    }

    void reconcile(long estimated, long actual) {
        long delta = estimated - actual; // positive = we overcharged, credit back
        if (delta == 0) return;
        long cur;
        do {
            cur = available.get();
            long next = Math.min(capacity, cur + delta);
            if (next == cur) return;
            if (!available.compareAndSet(cur, next)) continue;
            break;
        } while (true);
    }

    private void refill() {
        if (refillRatePerSecond == 0) return;
        long now = System.currentTimeMillis();
        long last = lastRefillMs.get();
        long elapsed = now - last;
        if (elapsed <= 0) return;
        if (!lastRefillMs.compareAndSet(last, now)) return; // another thread refilled
        long tokens = (elapsed * refillRatePerSecond) / 1000;
        if (tokens <= 0) return;
        long cur;
        do {
            cur = available.get();
            long next = Math.min(capacity, cur + tokens);
            if (next == cur) return;
        } while (!available.compareAndSet(cur, Math.min(capacity, cur + tokens)));
    }
}
