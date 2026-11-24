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

    private FallbackRouter routerFor(SlidingWindowCircuitBreaker primaryBreaker, Duration timeout) {
        return new FallbackRouter(new RouterConfig(
            new OpenAiProvider(primary.baseUrl(), "k"),
            new OpenAiProvider(fallback.baseUrl(), "k"),
            timeout,
            primaryBreaker,
            new SlidingWindowCircuitBreaker()
        ));
    }

    @Test
    void bitFlippedJson_tripsCircuitBreaker() {
        var breaker = new SlidingWindowCircuitBreaker(5, 3, 0.40, 500);
        var router = routerFor(breaker, Duration.ofSeconds(5));

        primary.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(bitFlippedBody(20))));

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

    @Test
    void malformedChunkedEncoding_tripsCircuitBreaker() {
        var breaker = new SlidingWindowCircuitBreaker(5, 3, 0.40, 500);
        var router = routerFor(breaker, Duration.ofSeconds(5));

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

    @Test
    void circuitBreaker_fullStateMachine_closedOpenHalfOpenClosed() throws Exception {
        var breaker = new SlidingWindowCircuitBreaker(5, 3, 0.40, 300);
        var router = routerFor(breaker, Duration.ofSeconds(5));

        assertEquals(CircuitBreakerState.CLOSED, breaker.state(), "should start CLOSED");

        primary.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(bitFlippedBody(15))));

        for (int i = 0; i < 6 && breaker.state() != CircuitBreakerState.OPEN; i++) {
            try { router.route(AiRequest.of("t", "gpt-4o", "ping")); } catch (ProviderException ignored) {}
        }
        assertEquals(CircuitBreakerState.OPEN, breaker.state(), "CB should be OPEN after failures");

        Thread.sleep(350);

        primary.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(validBody())));

        assertDoesNotThrow(() -> router.route(AiRequest.of("t", "gpt-4o", "ping")),
            "Probe request after open-duration should succeed");

        assertEquals(CircuitBreakerState.CLOSED, breaker.state(),
            "CB should return to CLOSED after successful probe in HALF_OPEN state");
    }

    @Test
    void circuitBreaker_halfOpenProbeFails_reopens() throws Exception {
        var breaker = new SlidingWindowCircuitBreaker(5, 3, 0.40, 300);
        var router = routerFor(breaker, Duration.ofSeconds(5));

        primary.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse().withFault(Fault.MALFORMED_RESPONSE_CHUNK)));

        for (int i = 0; i < 6 && breaker.state() != CircuitBreakerState.OPEN; i++) {
            try { router.route(AiRequest.of("t", "gpt-4o", "ping")); } catch (ProviderException ignored) {}
        }
        assertEquals(CircuitBreakerState.OPEN, breaker.state());

        Thread.sleep(350);

        try { router.route(AiRequest.of("t", "gpt-4o", "ping")); } catch (ProviderException ignored) {}

        assertEquals(CircuitBreakerState.OPEN, breaker.state(),
            "CB should re-OPEN when probe in HALF_OPEN state fails");
    }

    @Test
    void socketHang_fallbackServesAndCbRecordsTimeout() {
        var primaryBreaker = new SlidingWindowCircuitBreaker(5, 3, 0.40, 500);
        var router = routerFor(primaryBreaker, Duration.ofMillis(500));

        primary.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse().withFixedDelay(30_000).withStatus(200).withBody(validBody())));
        fallback.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(validBody())));

        int served = 0;
        for (int i = 0; i < 6; i++) {
            try {
                var resp = router.route(AiRequest.of("t", "gpt-4o", "ping"));
                if ("ok".equals(resp.content())) served++;
            } catch (ProviderException ignored) {}
        }

        assertTrue(served > 0, "Fallback should have served at least one request during socket hangs");
        assertNotEquals(CircuitBreakerState.CLOSED, primaryBreaker.state(),
            "Primary CB should have moved out of CLOSED after repeated timeout failures");
    }
}
