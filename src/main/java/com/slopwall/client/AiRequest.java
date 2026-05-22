package com.slopwall.client;

import java.time.Duration;

public record AiRequest(
    String tenantId,
    String model,
    String prompt,
    int maxTokens,
    Duration timeout
) {
    public static AiRequest of(String tenantId, String model, String prompt) {
        return new AiRequest(tenantId, model, prompt, 1024, Duration.ofSeconds(30));
    }

    public AiRequest withTimeout(Duration newTimeout) {
        return new AiRequest(tenantId, model, prompt, maxTokens, newTimeout);
    }
}
