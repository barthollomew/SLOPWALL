package com.slopwall;

import com.slopwall.cache.SemanticCache;
import com.slopwall.client.*;
import com.slopwall.observe.*;
import com.slopwall.ratelimit.*;
import com.slopwall.router.FallbackRouter;
import com.slopwall.shadow.ShadowTester;

public final class SlopwallGateway implements AutoCloseable {

    private static final int DEFAULT_TOKEN_ESTIMATE = 200;

    private final FallbackRouter router;
    private final RateLimiter rateLimiter;
    private final SemanticCache cache;           // may be null
    private final ShadowTester shadowTester;     // may be null

    private SlopwallGateway(Builder b) {
        this.router = b.router;
        this.rateLimiter = b.rateLimiter;
        this.cache = b.cache;
        this.shadowTester = b.shadowTester;
    }

    public AiResponse chat(AiRequest request) throws ProviderException {
        // 1. Rate limit gate (< 0.1ms, CAS-loop)
        int estimated = router.config().primary().estimateTokens(request);
        if (estimated < 0) estimated = DEFAULT_TOKEN_ESTIMATE;
        if (!rateLimiter.tryConsume(request.tenantId(), estimated)) {
            throw new ProviderException("gateway", 429,
                "rate limit exceeded for tenant: " + request.tenantId());
        }

        // 2. Semantic cache lookup (optional)
        if (cache != null) {
            var hit = cache.lookup(request.tenantId(), request.prompt());
            if (hit.isPresent()) {
                rateLimiter.reconcile(request.tenantId(), estimated, 0);
                emitCacheHit(request.tenantId(), 1.0f, 0L);
                emitLatency("cache", 0L, true, 0);
                return new AiResponse(hit.get(), 0, 0, "cache", true);
            }
        }

        // 3. Route (primary -> fallback via circuit breaker)
        long start = System.nanoTime();
        AiResponse response = router.route(request);
        long latencyMs = (System.nanoTime() - start) / 1_000_000;

        // 4. Reconcile token estimate with actuals
        int actual = response.promptTokens() + response.completionTokens();
        rateLimiter.reconcile(request.tenantId(), estimated, actual);

        // 5. JFR events
        emitTokenUsage(request, response, estimated);
        emitLatency(response.provider(), latencyMs, false, 0);

        // 6. Async cache store (best-effort virtual thread)
        if (cache != null) {
            Thread.ofVirtual().start(() ->
                cache.store(request.tenantId(), request.prompt(), response.content()));
        }

        // 7. Shadow test (async, sampled)
        if (shadowTester != null) {
            shadowTester.maybeShadow(request, response);
        }

        return response;
    }

    private void emitTokenUsage(AiRequest req, AiResponse resp, int estimated) {
        var e = new TokenUsageEvent();
        if (!e.isEnabled()) return;
        e.tenantId = req.tenantId();
        e.provider = resp.provider();
        e.promptTokens = resp.promptTokens();
        e.completionTokens = resp.completionTokens();
        e.estimatedTokens = estimated;
        e.commit();
    }

    private void emitCacheHit(String tenantId, float similarity, long embedMs) {
        var e = new CacheHitEvent();
        if (!e.isEnabled()) return;
        e.tenantId = tenantId;
        e.cosineSimilarity = similarity;
        e.embedLatencyMs = embedMs;
        e.commit();
    }

    private void emitLatency(String provider, long ms, boolean cacheHit, int status) {
        var e = new ProviderLatencyEvent();
        if (!e.isEnabled()) return;
        e.provider = provider;
        e.latencyMs = ms;
        e.cacheHit = cacheHit;
        e.httpStatus = status;
        e.commit();
    }

    @Override
    public void close() {
        if (shadowTester != null) shadowTester.close();
        if (cache instanceof AutoCloseable ac) {
            try { ac.close(); } catch (Exception ignored) {}
        }
    }

    public static Builder builder(FallbackRouter router, RateLimiter rateLimiter) {
        return new Builder(router, rateLimiter);
    }

    public static final class Builder {
        private final FallbackRouter router;
        private final RateLimiter rateLimiter;
        private SemanticCache cache;
        private ShadowTester shadowTester;

        private Builder(FallbackRouter router, RateLimiter rateLimiter) {
            this.router = router;
            this.rateLimiter = rateLimiter;
        }

        public Builder semanticCache(SemanticCache cache) { this.cache = cache; return this; }
        public Builder shadowTester(ShadowTester tester) { this.shadowTester = tester; return this; }
        public SlopwallGateway build() { return new SlopwallGateway(this); }
    }
}
