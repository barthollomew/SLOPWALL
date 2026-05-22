package com.slopwall.router;

import com.slopwall.client.*;
import java.util.concurrent.*;

@SuppressWarnings("preview")
public final class FallbackRouter {

    private final RouterConfig config;

    public FallbackRouter(RouterConfig config) { this.config = config; }

    public RouterConfig config() { return config; }

    public AiResponse route(AiRequest request) throws ProviderException {
        if (!config.primaryBreaker().allowRequest()) {
            return callWithBreaker(config.fallback(), config.fallbackBreaker(), request);
        }
        try {
            var resp = callWithTimeout(config.primary(), request.withTimeout(config.fallbackTimeout()));
            config.primaryBreaker().recordSuccess();
            return resp;
        } catch (ProviderException e) {
            config.primaryBreaker().recordFailure();
            if (e.triggersFallback()) {
                return callWithBreaker(config.fallback(), config.fallbackBreaker(), request);
            }
            throw e;
        }
    }

    private AiResponse callWithTimeout(AiProvider provider, AiRequest req) throws ProviderException {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            var task = scope.fork(() -> provider.call(req));
            try {
                scope.joinUntil(java.time.Instant.now().plus(req.timeout()));
            } catch (TimeoutException e) {
                throw new ProviderException(provider.name(), 0, "timed out after " + req.timeout(),
                    new java.net.http.HttpTimeoutException("timeout"));
            }
            // Pass through existing ProviderExceptions unchanged to preserve isTimeout() / isRetryable() semantics
            scope.throwIfFailed(cause -> cause instanceof ProviderException pe ? pe
                : new ProviderException(provider.name(), 0, cause.getMessage(), cause));
            return task.get();
        } catch (ProviderException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(provider.name(), 0, "interrupted");
        }
    }

    private AiResponse callWithBreaker(AiProvider provider, com.slopwall.breaker.CircuitBreaker breaker, AiRequest request) {
        if (!breaker.allowRequest()) {
            throw new ProviderException(provider.name(), 503, "circuit breaker open");
        }
        try {
            var resp = callWithTimeout(provider, request);
            breaker.recordSuccess();
            return resp;
        } catch (ProviderException e) {
            breaker.recordFailure();
            throw e;
        }
    }
}
