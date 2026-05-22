package com.slopwall.integration;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.slopwall.breaker.*;
import com.slopwall.client.*;
import com.slopwall.router.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import java.time.Duration;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

class CircuitBreakerIntegrationTest {

    @RegisterExtension
    static WireMockExtension primary = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort()).build();

    @RegisterExtension
    static WireMockExtension fallback = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort()).build();

    private static String openAiOkResponse(String content) {
        return """
            {"id":"test","object":"chat.completion","choices":[{"message":{"role":"assistant","content":"%s"},"finish_reason":"stop","index":0}],"usage":{"prompt_tokens":10,"completion_tokens":10,"total_tokens":20}}
            """.formatted(content);
    }

    @Test
    void primary429_tripsCircuitBreaker_routesToFallback() {
        primary.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse().withStatus(429).withBody("{\"error\":\"rate_limited\"}")));

        fallback.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(openAiOkResponse("fallback response"))));

        var primaryProvider = new OpenAiProvider(primary.baseUrl(), "test-key");
        var fallbackProvider = new OpenAiProvider(fallback.baseUrl(), "test-key");
        var breaker = new SlidingWindowCircuitBreaker();
        var config = new RouterConfig(primaryProvider, fallbackProvider,
            Duration.ofSeconds(2), breaker, new SlidingWindowCircuitBreaker());
        var router = new FallbackRouter(config);

        for (int i = 0; i < 8; i++) {
            var resp = router.route(AiRequest.of("t1", "gpt-4o", "hello"));
            assertEquals("openai", resp.provider());
            assertEquals("fallback response", resp.content());
        }

        assertTrue(
            breaker.state() == CircuitBreakerState.OPEN ||
            breaker.state() == CircuitBreakerState.HALF_OPEN,
            "Expected circuit OPEN, got: " + breaker.state()
        );
    }

    @Test
    void primary503_tripsCircuitBreaker_fallbackServes() {
        primary.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse().withStatus(503).withBody("{\"error\":\"unavailable\"}")));
        fallback.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(openAiOkResponse("from fallback"))));

        var config = RouterConfig.of(
            new OpenAiProvider(primary.baseUrl(), "k"),
            new OpenAiProvider(fallback.baseUrl(), "k"));
        var router = new FallbackRouter(config);

        var resp = router.route(AiRequest.of("t1", "gpt-4o", "test"));
        assertEquals("from fallback", resp.content());
    }
}
