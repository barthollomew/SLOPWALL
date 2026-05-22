package com.slopwall.router;

import com.slopwall.breaker.SlidingWindowCircuitBreaker;
import com.slopwall.client.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class FallbackRouterTest {

    private static AiProvider stubSuccess(String name, String content) {
        return new AiProvider() {
            public AiResponse call(AiRequest req) { return new AiResponse(content, 10, 10, name, false); }
            public int estimateTokens(AiRequest req) { return 20; }
            public String name() { return name; }
        };
    }

    private static AiProvider stubFailure(String name, int status) {
        return new AiProvider() {
            public AiResponse call(AiRequest req) { throw new ProviderException(name, status, "error"); }
            public int estimateTokens(AiRequest req) { return 20; }
            public String name() { return name; }
        };
    }

    private static AiProvider stubHanging(String name, Duration delay) {
        return new AiProvider() {
            public AiResponse call(AiRequest req) {
                try { Thread.sleep(delay.toMillis()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                throw new ProviderException(name, 0, "interrupted");
            }
            public int estimateTokens(AiRequest req) { return 20; }
            public String name() { return name; }
        };
    }

    @Test
    void primarySucceeds_fallbackNeverCalled() {
        var fallbackCalls = new AtomicInteger(0);
        var fallback = new AiProvider() {
            public AiResponse call(AiRequest req) { fallbackCalls.incrementAndGet(); return new AiResponse("f", 5, 5, "fb", false); }
            public int estimateTokens(AiRequest req) { return 10; }
            public String name() { return "fb"; }
        };
        var router = new FallbackRouter(RouterConfig.of(stubSuccess("primary", "ok"), fallback));
        var resp = router.route(AiRequest.of("t1", "gpt-4o", "hi"));
        assertEquals("primary", resp.provider());
        assertEquals(0, fallbackCalls.get());
    }

    @Test
    void primary503_routesToFallback() {
        var router = new FallbackRouter(RouterConfig.of(
            stubFailure("primary", 503), stubSuccess("fallback", "fallback-resp")));
        var resp = router.route(AiRequest.of("t1", "gpt-4o", "hi"));
        assertEquals("fallback", resp.provider());
    }

    @Test
    void primaryTimeout_routesToFallbackWithin2100ms() {
        // primary hangs 30s, fallbackTimeout is 2s
        var config = new RouterConfig(
            stubHanging("primary", Duration.ofSeconds(30)),
            stubSuccess("fallback", "quick"),
            Duration.ofSeconds(2),
            new SlidingWindowCircuitBreaker(),
            new SlidingWindowCircuitBreaker()
        );
        var router = new FallbackRouter(config);
        long start = System.currentTimeMillis();
        var resp = router.route(AiRequest.of("t1", "gpt-4o", "hi"));
        long elapsed = System.currentTimeMillis() - start;
        assertEquals("fallback", resp.provider());
        assertTrue(elapsed < 2_100, "Expected fallback within 2100ms, took " + elapsed + "ms");
    }

    @Test
    void primaryCircuitOpen_fallbackCalledDirectly() {
        var primaryCalls = new AtomicInteger(0);
        var primary = new AiProvider() {
            public AiResponse call(AiRequest req) { primaryCalls.incrementAndGet(); return new AiResponse("p", 5, 5, "primary", false); }
            public int estimateTokens(AiRequest req) { return 10; }
            public String name() { return "primary"; }
        };
        var breaker = new SlidingWindowCircuitBreaker();
        // Trip the breaker
        for (int i = 0; i < 10; i++) breaker.recordFailure();
        var config = new RouterConfig(primary, stubSuccess("fallback", "fb"), Duration.ofSeconds(2), breaker, new SlidingWindowCircuitBreaker());
        var router = new FallbackRouter(config);
        router.route(AiRequest.of("t1", "gpt-4o", "hi"));
        assertEquals(0, primaryCalls.get()); // primary never called when circuit open
    }

    @Test
    void bothFail_throwsProviderException() {
        var router = new FallbackRouter(RouterConfig.of(stubFailure("p", 503), stubFailure("f", 503)));
        assertThrows(ProviderException.class, () -> router.route(AiRequest.of("t1", "gpt-4o", "hi")));
    }
}
