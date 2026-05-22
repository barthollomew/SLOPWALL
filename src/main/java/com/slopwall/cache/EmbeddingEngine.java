package com.slopwall.cache;

public interface EmbeddingEngine extends AutoCloseable {
    /** Returns L2-normalized float[] embedding. Thread-safe. */
    float[] embed(String text);
    int dimensions();
    @Override void close();
}
