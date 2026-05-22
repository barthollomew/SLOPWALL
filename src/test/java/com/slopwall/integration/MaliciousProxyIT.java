package com.slopwall.integration;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.slopwall.breaker.CircuitBreakerState;
import com.slopwall.breaker.SlidingWindowCircuitBreaker;
import com.slopwall.client.*;
import com.slopwall.router.FallbackRouter;
import com.slopwall.router.RouterConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Malicious-proxy fault injection: validates that SLOPWALL's circuit breaker state
 * machine transitions correctly under three fault modes:
 *
 * <ol>
 *   <li>Bit-flipped JSON bodies:  CB records failures → opens → recovers via HALF_OPEN.</li>
 *   <li>Malformed chunked encoding: IOException path trips CB identically to JSON corruption.</li>
 *   <li>Full CB state-machine (CLOSED → OPEN → HALF_OPEN → CLOSED): validated end-to-end
 *       with fast CB parameters so the test completes without long sleeps.</li>
 * </ol>
 */
@SuppressWarnings("preview")
class MaliciousProxyIT {

    @RegisterExtension
    static WireMockExtension primary = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort()).build();

    @RegisterExtension
    static WireMockExtension fallback = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort()).build();

    private static String validBody() {
        return """
            {"id":"t","object":"chat.completion","choices":[{"message":{"role":"assistant",\
            "content":"ok"},"finish_reason":"stop","index":0}],"usage":{"prompt_tokens":5,\
            "completion_tokens":5,"total_tokens":10}}
            """;
    }

    private static byte[] bitFlippedBody(int numFlips) {
        byte[] bytes = validBody().getBytes(StandardCharsets.UTF_8);
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < numFlips; i++) {
            int pos = rng.nextInt(bytes.length);
            bytes[pos] ^= (byte) (1 << rng.nextInt(8));
        }
        return bytes;
    }

    // ── Test 1: Bit-flipped response → circuit breaker opens ─────────────────

    @Test
    void bitFlippedJson_tripsCircuitBreaker() {
        // Fast CB: window=5, minCalls=3, failureThreshold=40%, openDuration=500ms
        var breaker = new SlidingWindowCircuitBreaker(5, 3, 0.40, 500);
        var config = new RouterConfig(
            new OpenAiProvider(primary.baseUrl(), "k"),
            new OpenAiProvider(fallback.baseUrl(), "k"),
            Duration.ofSeconds(5),
            breaker,
            new SlidingWindowCircuitBreaker()
        );
        var router = new FallbackRouter(config);

        // Primary always returns bit-flipped garbage
        primary.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(bitFlippedBody(20))));

        // Send enough requests to trip the circuit breaker
        int failures = 0;
        for (int i = 0; i < 6 && breaker.state() != CircuitBreakerState.OPEN; i++) {
            try {
                router.route(AiRequest.of("t", "gpt-4o", "ping"));
            } catch (ProviderException e) {
                failures++;
            }
        }

        assertEquals(CircuitBreakerState.OPEN, breaker.state(),
            "CB should be OPEN after " + failures + " parse failures (window=5, threshold=40%)");
    }

    // ── Test 2: Malformed chunked encoding → circuit breaker opens ────────────

    @Test
    void malformedChunkedEncoding_tripsCircuitBreaker() {
        var breaker = new SlidingWindowCircuitBreaker(5, 3, 0.40, 500);
        var config = new RouterConfig(
            new OpenAiProvider(primary.baseUrl(), "k"),
            new OpenAiProvider(fallback.baseUrl(), "k"),
            Duration.ofSeconds(5),
            breaker,
            new SlidingWindowCircuitBreaker()
        );
        var router = new FallbackRouter(config);

        // Inject a malformed chunked response — Java's HttpClient will throw IOException.
        primary.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse().withFault(Fault.MALFORMED_RESPONSE_CHUNK)));

        int failures = 0;
        for (int i = 0; i < 6 && breaker.state() != CircuitBreakerState.OPEN; i++) {
            try {
                router.route(AiRequest.of("t", "gpt-4o", "ping"));
            } catch (ProviderException e) {
                failures++;
            }
        }

        assertEquals(CircuitBreakerState.OPEN, breaker.state(),
            "CB should be OPEN after " + failures +
            " malformed-chunk failures (window=5, threshold=40%)");
    }

    // ── Test 3: Full CB state machine CLOSED → OPEN → HALF_OPEN → CLOSED ─────

    @Test
    void circuitBreaker_fullStateMachine_closedOpenHalfOpenClosed() throws Exception {
        // Fast parameters so transitions happen within milliseconds.
        var breaker = new SlidingWindowCircuitBreaker(5, 3, 0.40, 300 /* ms */);
        var config = new RouterConfig(
            new OpenAiProvider(primary.baseUrl(), "k"),
            new OpenAiProvider(fallback.baseUrl(), "k"),
            Duration.ofSeconds(5),
            breaker,
            new SlidingWindowCircuitBreaker()
        );
        var router = new FallbackRouter(config);

        assertEquals(CircuitBreakerState.CLOSED, breaker.state(), "should start CLOSED");

        // ── Phase 1: Trip to OPEN with bit-flipped responses ──────────────────
        primary.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(bitFlippedBody(15))));

        for (int i = 0; i < 6 && breaker.state() != CircuitBreakerState.OPEN; i++) {
            try { router.route(AiRequest.of("t", "gpt-4o", "ping")); } catch (ProviderException ignored) {}
        }
        assertEquals(CircuitBreakerState.OPEN, breaker.state(), "CB should be OPEN after failures");

        // ── Phase 2: Wait for open-duration to expire → HALF_OPEN on next call ─
        Thread.sleep(350); // > openDuration=300ms

        // Stub primary to return valid JSON for the probe
        primary.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(validBody())));

        // First call after timeout: CB transitions to HALF_OPEN, then probe succeeds → CLOSED
        assertDoesNotThrow(() -> router.route(AiRequest.of("t", "gpt-4o", "ping")),
            "Probe request after open-duration should succeed");

        assertEquals(CircuitBreakerState.CLOSED, breaker.state(),
            "CB should return to CLOSED after successful probe in HALF_OPEN state");
    }

    // ── Test 4: HALF_OPEN → OPEN when probe fails ─────────────────────────────

    @Test
    void circuitBreaker_halfOpenProbeFails_reopens() throws Exception {
        var breaker = new SlidingWindowCircuitBreaker(5, 3, 0.40, 300);
        var config = new RouterConfig(
            new OpenAiProvider(primary.baseUrl(), "k"),
            new OpenAiProvider(fallback.baseUrl(), "k"),
            Duration.ofSeconds(5),
            breaker,
            new SlidingWindowCircuitBreaker()
        );
        var router = new FallbackRouter(config);

        // Trip the CB
        primary.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse().withFault(Fault.MALFORMED_RESPONSE_CHUNK)));

        for (int i = 0; i < 6 && breaker.state() != CircuitBreakerState.OPEN; i++) {
            try { router.route(AiRequest.of("t", "gpt-4o", "ping")); } catch (ProviderException ignored) {}
        }
        assertEquals(CircuitBreakerState.OPEN, breaker.state());

        // Wait for open-duration
        Thread.sleep(350);

        // Probe still fails → should re-open
        try { router.route(AiRequest.of("t", "gpt-4o", "ping")); } catch (ProviderException ignored) {}

        assertEquals(CircuitBreakerState.OPEN, breaker.state(),
            "CB should re-OPEN when probe in HALF_OPEN state fails");
    }

    // ── Test 5: Socket hang → fallback serves within timeout ─────────────────
    // (validates integration between existing fault-injection and CB state accounting)

    @Test
    void socketHang_fallbackServesAndCbRecordsTimeout() {
        var primaryBreaker  = new SlidingWindowCircuitBreaker(5, 3, 0.40, 500);
        var fallbackBreaker = new SlidingWindowCircuitBreaker();
        var config = new RouterConfig(
            new OpenAiProvider(primary.baseUrl(), "k"),
            new OpenAiProvider(fallback.baseUrl(), "k"),
            Duration.ofMillis(500),
            primaryBreaker,
            fallbackBreaker
        );
        var router = new FallbackRouter(config);

        primary.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse().withFixedDelay(30_000).withStatus(200).withBody(validBody())));
        fallback.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(validBody())));

        // Send enough requests to trip primary CB via timeouts
        int served = 0;
        for (int i = 0; i < 6; i++) {
            try {
                var resp = router.route(AiRequest.of("t", "gpt-4o", "ping"));
                if ("ok".equals(resp.content())) served++;
            } catch (ProviderException ignored) {}
        }

        assertTrue(served > 0, "Fallback should have served at least one request during socket hangs");

        // Primary breaker should have recorded failures from the timeouts
        assertNotEquals(CircuitBreakerState.CLOSED, primaryBreaker.state(),
            "Primary CB should have moved out of CLOSED after repeated timeout failures");
    }
}
