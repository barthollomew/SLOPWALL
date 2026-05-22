package com.slopwall.cache;

public final class CosineDistance {
    private CosineDistance() {}

    public static float similarity(float[] a, float[] b) {
        if (a.length != b.length)
            throw new IllegalArgumentException("Vector dimension mismatch: " + a.length + " vs " + b.length);
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot   += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0.0 ? 0.0f : (float) (dot / denom);
    }
}
