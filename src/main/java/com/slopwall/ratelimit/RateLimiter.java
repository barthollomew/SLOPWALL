package com.slopwall.ratelimit;

public interface RateLimiter {
    boolean tryConsume(String tenantId, long tokens);
    void reconcile(String tenantId, long estimated, long actual);
    void registerTenant(String tenantId, long capacityTokens, long refillRatePerSecond);
}
