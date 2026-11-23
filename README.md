# SLOPWALL

Java 21 gateway library that wraps LLM API calls with circuit breaking, token-budget enforcement, provider failover, and semantic caching. Drop it in front of any OpenAI, Anthropic, or Ollama endpoint.

## What it does

| Component | Behavior |
|---|---|
| `TokenBucketRateLimiter` | Per-tenant token budget. Lock-free CAS loop. Reconciles estimate vs actual after each call. |
| `SlidingWindowCircuitBreaker` | Trips at configurable failure rate over a rolling window. Auto-recovers via HALF_OPEN probe. |
| `FallbackRouter` | Runs primary call under `StructuredTaskScope`. On timeout or retryable error, routes to fallback provider. |
| `SqliteSemanticCache` | Stores prompt embeddings in WAL-mode SQLite. Exact-hash shortcut for identical prompts, cosine scan for near-matches. |
| `ShadowTester` | Fire-and-forget secondary calls at a configurable sampling rate. Uses virtual threads. |
| JFR events | `TokenUsage`, `CacheHit`, `ProviderLatency` events emitted via JDK Flight Recorder. Zero overhead when no recording is active. |

## Requirements

- Java 21 (preview features enabled: `StructuredTaskScope`)
- No external process dependencies. SQLite runs in-process. WireMock used only in tests.

## Quick start

```java
// Wire up components
var limiter = new TokenBucketRateLimiter();
limiter.registerTenant("tenant-a", 100_000, 500); // 100k burst, 500 tokens/sec refill

var router = new FallbackRouter(RouterConfig.of(
    new OpenAiProvider("https://api.openai.com", System.getenv("OPENAI_API_KEY")),
    new AnthropicProvider("https://api.anthropic.com", System.getenv("ANTHROPIC_API_KEY"))
));

var gateway = SlopwallGateway.builder(router, limiter).build();

// Call it
AiResponse resp = gateway.chat(AiRequest.of("tenant-a", "gpt-4o", "Summarize this text."));
System.out.println(resp.content());
```

With semantic caching:

```java
var cache = new SqliteSemanticCache("/var/data/slopwall.db", embeddingEngine, 0.95f);

var gateway = SlopwallGateway.builder(router, limiter)
    .semanticCache(cache)
    .build();
```

## Test Coverage

The test suite contains 35 tests covering all core components and failure modes:

- Rate limiter tests verify atomic CAS consumption, refill behavior, unknown tenant handling, and token reconciliation between estimated and actual usage.
- Sliding window circuit breaker tests verify state transitions from CLOSED to OPEN on failure rate thresholds, window sliding logic, and probe recovery via HALF_OPEN.
- Structured concurrency tests verify timeout enforcement and fallback routing using `StructuredTaskScope.ShutdownOnFailure`.
- SQLite semantic cache tests verify exact-hash lookup for identical prompts, cosine similarity vector scanning, and WAL mode concurrent read-write behavior.
- In-process WireMock provider tests verify failover on HTTP 429 and 503 status codes, timeout handling, and successful primary recovery.

## Rate limiting

Unknown tenants fail open. Register before use:

```java
limiter.registerTenant(tenantId, capacityTokens, refillRatePerSecond);
```

Estimate and actual reconciliation runs automatically after each call. When the provider returns actual token counts, the bucket is corrected to reflect real consumption.

## Circuit breaker

Default: trips when more than 20 percent of the last 10 calls fail, stays open for 10 seconds.

```java
// Custom thresholds
var breaker = new SlidingWindowCircuitBreaker(
    20,      // window size
    10,      // minimum calls before tripping
    0.15,    // 15 percent failure threshold
    5_000    // reopen probe after 5s
);
```

## Timeout and fallback

`RouterConfig.fallbackTimeout` controls how long the primary has before the scope cancels it and routes to the fallback:

```java
var config = new RouterConfig(primary, fallback, Duration.ofSeconds(3),
    primaryBreaker, fallbackBreaker);
```

Both 429 and 503 HTTP errors and network timeouts trigger fallback. Non-retryable errors (400, 401, 500) propagate to the caller as `ProviderException`.

## Build

```bash
./gradlew clean build
```

35 tests. No Docker required. WireMock stubs HTTP in-process.

## JFR

```bash
jcmd <pid> JFR.start filename=slopwall.jfr settings=default
# run load
jcmd <pid> JFR.stop
```

Events: `com.slopwall.TokenUsage`, `com.slopwall.CacheHit`, `com.slopwall.ProviderLatency`.

See [ARCHITECTURE.md](ARCHITECTURE.md) for design details and [RUNBOOK.md](RUNBOOK.md) for tuning and operational guidance.
