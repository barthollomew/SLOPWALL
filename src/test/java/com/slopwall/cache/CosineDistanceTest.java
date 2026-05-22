package com.slopwall.cache;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CosineDistanceTest {

    @Test
    void identicalVectorsHaveSimilarityOne() {
        float[] v = {0.5f, 0.3f, 0.8f};
        assertEquals(1.0f, CosineDistance.similarity(v, v), 1e-5f);
    }

    @Test
    void orthogonalVectorsHaveSimilarityZero() {
        float[] a = {1f, 0f, 0f};
        float[] b = {0f, 1f, 0f};
        assertEquals(0.0f, CosineDistance.similarity(a, b), 1e-5f);
    }

    @Test
    void knownSimilarity() {
        // [1,0] vs [1,1]/sqrt(2) -- cosine = 1/sqrt(2) ~ 0.7071
        float[] a = {1f, 0f};
        float[] b = {(float)(1/Math.sqrt(2)), (float)(1/Math.sqrt(2))};
        assertEquals(0.7071f, CosineDistance.similarity(a, b), 1e-3f);
    }

    @Test
    void zeroVectorReturnsSafeZero() {
        float[] zero = {0f, 0f, 0f};
        float[] v = {1f, 2f, 3f};
        assertEquals(0.0f, CosineDistance.similarity(zero, v), 1e-5f);
    }

    @Test
    void dimensionMismatchThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> CosineDistance.similarity(new float[]{1f}, new float[]{1f, 2f}));
    }
}
