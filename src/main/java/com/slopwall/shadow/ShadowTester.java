package com.slopwall.shadow;

import com.slopwall.client.*;
import java.util.concurrent.*;

public final class ShadowTester implements AutoCloseable {

    private final AiProvider shadowProvider;
    private final double trafficFraction;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ShadowTester(AiProvider shadowProvider, double trafficFraction) {
        if (trafficFraction < 0 || trafficFraction > 1)
            throw new IllegalArgumentException("trafficFraction must be in [0,1]");
        this.shadowProvider = shadowProvider;
        this.trafficFraction = trafficFraction;
    }

    public void maybeShadow(AiRequest request, AiResponse primaryResponse) {
        if (ThreadLocalRandom.current().nextDouble() >= trafficFraction) return;
        executor.submit(() -> {
            try {
                shadowProvider.call(request);
            } catch (ProviderException ignored) {
                // shadow failures are always silent
            }
        });
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
