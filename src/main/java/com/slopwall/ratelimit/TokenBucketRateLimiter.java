package com.slopwall.ratelimit;

import java.util.concurrent.ConcurrentHashMap;

public final class TokenBucketRateLimiter implements RateLimiter {
    private final ConcurrentHashMap<String, TenantBucket> buckets = new ConcurrentHashMap<>();

    @Override
    public void registerTenant(String tenantId, long capacityTokens, long refillRatePerSecond) {
        buckets.put(tenantId, new TenantBucket(capacityTokens, refillRatePerSecond));
    }

    @Override
    public boolean tryConsume(String tenantId, long tokens) {
        var bucket = buckets.get(tenantId);
        if (bucket == null) return true; // fail-open for unknown tenants
        return bucket.tryConsume(tokens);
    }

    @Override
    public void reconcile(String tenantId, long estimated, long actual) {
        var bucket = buckets.get(tenantId);
        if (bucket != null) bucket.reconcile(estimated, actual);
    }
}
