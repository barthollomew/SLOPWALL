# SLOPWALL Runbook

## Tuning the Circuit Breaker

`SlidingWindowCircuitBreaker(windowSize, minCalls, failureThreshold, openDurationMs)`

| Parameter | Default | When to change |
|-----------|---------|----------------|
| windowSize | 10 | Increase for smoother signal on high-traffic providers |
| minCalls | 5 | Increase to avoid tripping on cold-start noise |
| failureThreshold | 0.20 | Lower (e.g. 0.10) for stricter protection; raise for noisy providers |
| openDurationMs | 10000 | Lower for faster recovery; raise if provider needs more time to heal |

## Tuning the Token Bucket

`registerTenant(tenantId, capacityTokens, refillRatePerSecond)`

- `capacityTokens`: peak burst budget. Set to the maximum tokens one tenant may use
  before being throttled regardless of rate.
- `refillRatePerSecond`: sustained throughput. 0 means no refill (one-shot budget).
- After each call, `reconcile(estimated, actual)` corrects the deduction. If actual
  consistently exceeds estimated (Anthropic heuristic), increase the estimate offset
  in the caller or use a larger safety margin.

## Updating the Embedding Model

1. Obtain the new ONNX model file (e.g. `all-MiniLM-L6-v2.onnx`).
2. Replace the file at the path passed to `OnnxEmbeddingEngine(modelPath)`.
3. If the model uses a different tokenizer, replace `simpleTokenize()` in
   `OnnxEmbeddingEngine` with the appropriate tokenizer (recommended:
   `ai.djl:tokenizers` Maven artifact).
4. Clear the `semantic_cache` table: `DELETE FROM semantic_cache;`
   Stale embeddings from the old model are dimensionally incompatible.
5. Verify with `./gradlew test` (unit tests use a stub engine and are unaffected).

## Running the Slow ONNX Integration Test

Set the model path, then run with the `slow` tag included:

```bash
export SLOPWALL_ONNX_MODEL_PATH=/path/to/all-MiniLM-L6-v2.onnx
./gradlew test -Pinclude-slow
```

The default build excludes `@Tag("slow")` tests. To include them, edit
`build.gradle.kts` and remove or change the `excludeTags("slow")` line.

## JFR Recording

Start a recording on a running JVM:

```bash
jcmd <pid> JFR.start filename=slopwall.jfr settings=default
# ... run load ...
jcmd <pid> JFR.stop
```

Events to watch in JDK Mission Control:
- `com.slopwall.TokenUsage` - actual vs estimated token drift per tenant
- `com.slopwall.ProviderLatency` - p99 latency by provider; spikes indicate circuit
  breaker is about to trip
- `com.slopwall.CacheHit` - cache hit rate and embed latency; high embed latency
  suggests the ONNX model needs thread tuning

## Health Checks

- Circuit breaker state: `circuitBreaker.state()` returns CLOSED/OPEN/HALF_OPEN
- Token bucket level: no direct read API; infer from reconcile deltas in JFR events
- Cache hit ratio: count `com.slopwall.CacheHit` events vs total `chat()` calls
