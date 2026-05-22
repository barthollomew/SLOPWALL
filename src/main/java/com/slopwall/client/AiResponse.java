package com.slopwall.client;

public record AiResponse(
    String content,
    int promptTokens,
    int completionTokens,
    String provider,
    boolean cacheHit
) {}
