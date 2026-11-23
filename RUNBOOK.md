# SLOPWALL Runbook

## Tuning the Circuit Breaker

Parameters: `windowSize`, `minCalls`, `failureThreshold`, `openDurationMs`

| Parameter | Default | When to change |
|---|---|---|
| windowSize | 10 | Increase for smoother signal on high traffic providers |
| minCalls | 5 | Increase to avoid tripping on cold start noise |
| failureThreshold | 0.20 | Lower for stricter protection or raise for noisy providers |
| openDurationMs | 10000 | Lower for faster recovery or raise if provider needs more time |

## Tuning the Token Bucket

Parameters: `tenantId`, `capacityTokens`, `refillRatePerSecond`

- `capacityTokens`: peak burst budget. Set to the maximum tokens one tenant may use before throttling.
- `refillRatePerSecond`: sustained throughput. Zero means no refill for one-shot budgets.
- After each call, reconciliation corrects the token deduction. If actual consumption consistently exceeds estimates, increase the estimate offset in the caller or use a larger safety margin.

## Updating the Embedding Model

1. Obtain the new ONNX model file.
2. Replace the file at the path passed to the embedding engine.
3. If the model uses a different tokenizer, replace the tokenizer method appropriately.
4. Clear the semantic cache table with `DELETE FROM semantic_cache;` because stale embeddings from old models are incompatible.
5. Verify with gradle test.

## Running the Slow ONNX Integration Test

Set the model path and run with the slow tag included:

```bash
export SLOPWALL_ONNX_MODEL_PATH=/path/to/model.onnx
./gradlew test -Pinclude-slow
```

The default build excludes slow tests.

## JFR Recording

Start a recording on a running JVM:

```bash
jcmd <pid> JFR.start filename=slopwall.jfr settings=default
# run load
jcmd <pid> JFR.stop
```

Events to watch in JDK Mission Control:
- Token usage: actual versus estimated token drift per tenant
- Provider latency: p99 latency by provider where spikes indicate circuit breaker tripping
- Cache hit: cache hit rate and embed latency where high embed latency suggests thread tuning

## Health Checks

- Circuit breaker state: returns closed, open, or half open
- Token bucket level: infer from reconciliation deltas in JFR events
- Cache hit ratio: count cache hit events against total chat calls
