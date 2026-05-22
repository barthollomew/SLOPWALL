package com.slopwall.client;

public interface AiProvider {
    /**
     * Synchronous blocking call. Must respect AiRequest.timeout().
     * Throws ProviderException on any error (HTTP error, timeout, network failure).
     */
    AiResponse call(AiRequest request) throws ProviderException;

    /**
     * Pre-flight token estimate used by the rate limiter.
     * Returns -1 if estimation is not supported (Ollama).
     * The gateway debits a configurable default (200 tokens) when -1 is returned
     * and reconciles after the response.
     */
    int estimateTokens(AiRequest request);

    /** Human-readable provider name, e.g. "openai", "anthropic", "ollama". */
    String name();
}
