package com.slopwall.integration;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.slopwall.SlopwallGateway;
import com.slopwall.client.*;
import com.slopwall.ratelimit.*;
import com.slopwall.router.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

class RateLimitIntegrationTest {

    @RegisterExtension
    static WireMockExtension server = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort()).build();

    private static String okBody() {
        return """
            {"id":"t","object":"chat.completion","choices":[{"message":{"role":"assistant","content":"hi"},"finish_reason":"stop","index":0}],"usage":{"prompt_tokens":50,"completion_tokens":50,"total_tokens":100}}
            """;
    }

    @Test
    void tenantBudgetExhausted_gatewayThrows429() {
        server.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(okBody())));

        var limiter = new TokenBucketRateLimiter();
        limiter.registerTenant("tenant-a", 500, 0);

        var provider = new OpenAiProvider(server.baseUrl(), "test-key");
        var router = new FallbackRouter(RouterConfig.of(provider, provider));
        var gateway = SlopwallGateway.builder(router, limiter).build();

        // maxTokens=50 so estimate ~51 tokens; budget=500 allows ~5 calls before exhaustion
        var req = new AiRequest("tenant-a", "gpt-4o", "hello", 50, java.time.Duration.ofSeconds(10));
        int succeeded = 0;
        int rejected = 0;
        for (int i = 0; i < 6; i++) {
            try {
                gateway.chat(req);
                succeeded++;
            } catch (ProviderException e) {
                assertEquals(429, e.httpStatus());
                rejected++;
            }
        }
        assertTrue(succeeded >= 1, "At least one request should succeed");
        assertTrue(rejected >= 1, "At least one request should be rate limited");
    }

    @Test
    void concurrentTenants_budgetsAreIsolated() throws InterruptedException {
        server.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(okBody())));

        var limiter = new TokenBucketRateLimiter();
        limiter.registerTenant("t-a", 200, 0);
        limiter.registerTenant("t-b", 200, 0);

        var provider = new OpenAiProvider(server.baseUrl(), "key");
        var router = new FallbackRouter(RouterConfig.of(provider, provider));
        var gateway = SlopwallGateway.builder(router, limiter).build();

        var tASucceeded = new AtomicInteger(0);
        var tBSucceeded = new AtomicInteger(0);
        var latch = new CountDownLatch(1);
        var threads = new ArrayList<Thread>(20);

        for (int i = 0; i < 10; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                try { gateway.chat(AiRequest.of("t-a", "gpt-4o", "hi")); tASucceeded.incrementAndGet(); }
                catch (ProviderException ignored) {}
            }));
            threads.add(Thread.ofVirtual().start(() -> {
                try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                try { gateway.chat(AiRequest.of("t-b", "gpt-4o", "hi")); tBSucceeded.incrementAndGet(); }
                catch (ProviderException ignored) {}
            }));
        }
        latch.countDown();
        for (var t : threads) t.join();

        assertTrue(tASucceeded.get() <= 2, "tenant-a over budget: " + tASucceeded);
        assertTrue(tBSucceeded.get() <= 2, "tenant-b over budget: " + tBSucceeded);
    }
}
