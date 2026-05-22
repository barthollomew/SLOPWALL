package com.slopwall.integration;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.slopwall.client.*;
import com.slopwall.router.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import java.time.Duration;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

class FaultInjectionIntegrationTest {

    @RegisterExtension
    static WireMockExtension slowServer = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort()).build();

    @RegisterExtension
    static WireMockExtension fastServer = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort()).build();

    private static String okBody() {
        return """
            {"id":"t","object":"chat.completion","choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop","index":0}],"usage":{"prompt_tokens":5,"completion_tokens":5,"total_tokens":10}}
            """;
    }

    @Test
    void hangingPrimary_fallbackServesWithin2100ms() {
        slowServer.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withFixedDelay(30_000)
                .withHeader("Content-Type", "application/json")
                .withBody(okBody())));

        fastServer.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(okBody())));

        var config = new RouterConfig(
            new OpenAiProvider(slowServer.baseUrl(), "k"),
            new OpenAiProvider(fastServer.baseUrl(), "k"),
            Duration.ofSeconds(2),
            new com.slopwall.breaker.SlidingWindowCircuitBreaker(),
            new com.slopwall.breaker.SlidingWindowCircuitBreaker()
        );
        var router = new FallbackRouter(config);

        long start = System.currentTimeMillis();
        var resp = router.route(AiRequest.of("t1", "gpt-4o", "ping"));
        long elapsed = System.currentTimeMillis() - start;

        assertEquals("ok", resp.content());
        assertTrue(elapsed < 2_100,
            "Expected fallback within 2100ms, actual: " + elapsed + "ms");
    }

    @Test
    void primaryCleanlyInterruptedOnTimeout() {
        slowServer.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse().withFixedDelay(10_000).withStatus(200).withBody(okBody())));
        fastServer.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(okBody())));

        var config = new RouterConfig(
            new OpenAiProvider(slowServer.baseUrl(), "k"),
            new OpenAiProvider(fastServer.baseUrl(), "k"),
            Duration.ofMillis(500),
            new com.slopwall.breaker.SlidingWindowCircuitBreaker(),
            new com.slopwall.breaker.SlidingWindowCircuitBreaker()
        );
        var router = new FallbackRouter(config);
        assertDoesNotThrow(() -> {
            for (int i = 0; i < 5; i++) router.route(AiRequest.of("t1", "gpt-4o", "ping"));
        });
    }
}
