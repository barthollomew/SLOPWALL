package com.slopwall.client;

import java.net.http.HttpTimeoutException;

public class ProviderException extends RuntimeException {
    private final int httpStatus;
    private final String provider;

    public ProviderException(String provider, int httpStatus, String message) {
        super("[" + provider + "] HTTP " + httpStatus + ": " + message);
        this.provider = provider;
        this.httpStatus = httpStatus;
    }

    public ProviderException(String provider, int httpStatus, String message, Throwable cause) {
        super("[" + provider + "] HTTP " + httpStatus + ": " + message, cause);
        this.provider = provider;
        this.httpStatus = httpStatus;
    }

    public int httpStatus() { return httpStatus; }
    public String provider() { return provider; }

    /** 429 (rate limit) or 503 (unavailable) -- eligible for fallback routing. */
    public boolean isRetryable() { return httpStatus == 429 || httpStatus == 503; }

    /** httpStatus 0 with HttpTimeoutException cause -- eligible for fallback routing. */
    public boolean isTimeout() {
        return httpStatus == 0 && getCause() instanceof HttpTimeoutException;
    }

    /** Either retryable HTTP error or a network timeout. */
    public boolean triggersFallback() { return isRetryable() || isTimeout(); }
}
