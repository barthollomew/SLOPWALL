package com.slopwall.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SemanticCacheTest {

    private SqliteSemanticCache cache;

    // Stub: first char determines direction (same first char = same vector = cosine 1.0)
    private final EmbeddingEngine stubEngine = new EmbeddingEngine() {
        @Override public float[] embed(String text) {
            float[] v = new float[4];
            int dir = text.charAt(0) % 4;
            v[dir] = 1.0f;
            return v;
        }
        @Override public int dimensions() { return 4; }
        @Override public void close() {}
    };

    @BeforeEach
    void setUp() throws Exception {
        cache = new SqliteSemanticCache(":memory:", stubEngine, 0.95f);
    }

    @AfterEach
    void tearDown() throws Exception {
        cache.close();
    }

    @Test
    void exactPromptHitReturnsResponse() {
        cache.store("t1", "hello world", "Hi there!");
        var result = cache.lookup("t1", "hello world");
        assertTrue(result.isPresent());
        assertEquals("Hi there!", result.get());
    }

    @Test
    void differentTenantDoesNotHit() {
        cache.store("t1", "hello world", "Hi there!");
        var result = cache.lookup("t2", "hello world");
        assertFalse(result.isPresent());
    }

    @Test
    void cacheMissForLowSimilarityPrompt() {
        // "hello world" starts with 'h' -> direction ('h'%4 = 0)
        // "apple pie" starts with 'a' -> direction ('a'%4 = 1)
        // cosine is 0 -> miss
        cache.store("t1", "hello world", "response A");
        var result = cache.lookup("t1", "apple pie");
        assertFalse(result.isPresent());
    }

    @Test
    void nearIdenticalPromptHitsCache() {
        // Both start with 'h' -> same direction vector -> cosine = 1.0 > 0.95 -> hit
        cache.store("t1", "hello world", "response A");
        var result = cache.lookup("t1", "how are you");
        assertTrue(result.isPresent());
        assertEquals("response A", result.get());
    }

    @Test
    void storingMultiplePromptsDoesNotCorrupt() {
        for (int i = 0; i < 20; i++) {
            cache.store("t1", "prompt-" + i, "response-" + i);
        }
        var result = cache.lookup("t1", "prompt-0");
        assertTrue(result.isPresent());
    }
}
