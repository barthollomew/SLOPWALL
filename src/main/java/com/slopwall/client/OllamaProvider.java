package com.slopwall.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;

public final class OllamaProvider implements AiProvider {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http;
    private final String baseUrl;

    public OllamaProvider(String baseUrl) {
        this.baseUrl = baseUrl;
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
                "prompt", req.prompt(),
                "stream", false
            ));
        } catch (IOException e) {
            throw new ProviderException(name(), 0, "JSON serialization failed", e);
        }

        var httpReq = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/generate"))
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
            var content = tree.at("/response").asText();
            var promptTokens = tree.at("/prompt_eval_count").asInt(0);
            var completionTokens = tree.at("/eval_count").asInt(0);
            return new AiResponse(content, promptTokens, completionTokens, name(), false);
        } catch (IOException e) {
            throw new ProviderException(name(), 0, "JSON parse failed", e);
        }
    }

    @Override
    public int estimateTokens(AiRequest req) {
        return -1; // Ollama reports actuals in response; gateway uses default 200 estimate
    }

    @Override
    public String name() { return "ollama"; }
}
