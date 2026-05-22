package com.slopwall.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class OpenAiProvider implements AiProvider {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http;
    private final String baseUrl;
    private final String apiKey;
    private final EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();

    public OpenAiProvider(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    @Override
    public AiResponse call(AiRequest req) throws ProviderException {
        String body;
        try {
            body = JSON.writeValueAsString(Map.of(
                "model", req.model(),
                "messages", List.of(Map.of("role", "user", "content", req.prompt())),
                "max_tokens", req.maxTokens()
            ));
        } catch (IOException e) {
            throw new ProviderException(name(), 0, "JSON serialization failed", e);
        }

        var httpReq = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/v1/chat/completions"))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .timeout(req.timeout())
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> resp;
        try {
            resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new ProviderException(name(), 0, "request timed out", e);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ProviderException(name(), 0, e.getMessage(), e);
        }

        if (resp.statusCode() != 200) {
            throw new ProviderException(name(), resp.statusCode(), resp.body());
        }

        try {
            var tree = JSON.readTree(resp.body());
            var content = tree.at("/choices/0/message/content").asText();
            var promptTokens = tree.at("/usage/prompt_tokens").asInt();
            var completionTokens = tree.at("/usage/completion_tokens").asInt();
            return new AiResponse(content, promptTokens, completionTokens, name(), false);
        } catch (IOException e) {
            throw new ProviderException(name(), 0, "JSON parse failed: " + e.getMessage(), e);
        }
    }

    @Override
    public int estimateTokens(AiRequest req) {
        var enc = registry.getEncodingForModel(req.model())
            .orElse(registry.getEncoding(EncodingType.CL100K_BASE));
        return enc.countTokens(req.prompt()) + req.maxTokens();
    }

    @Override
    public String name() { return "openai"; }
}
