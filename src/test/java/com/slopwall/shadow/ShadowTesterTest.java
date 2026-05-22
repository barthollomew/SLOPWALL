package com.slopwall.shadow;

import com.slopwall.client.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ShadowTesterTest {

    @Test
    void at100Percent_everyCallShadows() throws Exception {
        var shadowCalls = new AtomicInteger(0);
        var shadow = new AiProvider() {
            public AiResponse call(AiRequest req) { shadowCalls.incrementAndGet(); return new AiResponse("s", 1, 1, "shadow", false); }
            public int estimateTokens(AiRequest req) { return 1; }
            public String name() { return "shadow"; }
        };
        var tester = new ShadowTester(shadow, 1.0);
        var primary = new AiResponse("p", 10, 10, "primary", false);
        for (int i = 0; i < 10; i++) tester.maybeShadow(AiRequest.of("t1", "m", "hi"), primary);
        tester.close(); // waits for all virtual threads
        assertEquals(10, shadowCalls.get());
    }

    @Test
    void at0Percent_nothingShadowed() throws Exception {
        var shadowCalls = new AtomicInteger(0);
        var shadow = new AiProvider() {
            public AiResponse call(AiRequest req) { shadowCalls.incrementAndGet(); return new AiResponse("s", 1, 1, "s", false); }
            public int estimateTokens(AiRequest req) { return 1; }
            public String name() { return "s"; }
        };
        var tester = new ShadowTester(shadow, 0.0);
        var primary = new AiResponse("p", 10, 10, "primary", false);
        for (int i = 0; i < 20; i++) tester.maybeShadow(AiRequest.of("t1", "m", "hi"), primary);
        tester.close();
        assertEquals(0, shadowCalls.get());
    }

    @Test
    void shadowExceptionDoesNotPropagate() throws Exception {
        var shadow = new AiProvider() {
            public AiResponse call(AiRequest req) { throw new ProviderException("s", 500, "boom"); }
            public int estimateTokens(AiRequest req) { return 1; }
            public String name() { return "s"; }
        };
        var tester = new ShadowTester(shadow, 1.0);
        var primary = new AiResponse("p", 10, 10, "primary", false);
        assertDoesNotThrow(() -> {
            tester.maybeShadow(AiRequest.of("t1", "m", "hi"), primary);
            tester.close();
        });
    }
}
