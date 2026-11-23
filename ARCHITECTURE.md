# SLOPWALL Architecture

## Request Flow

```
Caller
  |
  v
SlopwallGateway.chat()
  |
  +-- RateLimiter.tryConsume()        [CAS loop, < 0.1ms]
  |
  +-- SemanticCache.lookup()          [optional; SQLite + cosine scan]
  |     hit -> return cached response
  |
  +-- FallbackRouter.route()
  |     |
  |     +-- primaryBreaker.allowRequest()
  |     |     OPEN -> skip primary, go direct to fallback
  |     |
  |     +-- callWithTimeout(primary, fallbackTimeout)
  |     |     success -> recordSuccess(), return
  |     |     retryable/timeout -> recordFailure(), go to fallback
  |     |
  |     +-- callWithBreaker(fallback, fallbackBreaker, request)
  |
  +-- RateLimiter.reconcile()         [correct estimated vs actual tokens]
  |
  +-- JFR event commit                [TokenUsage, ProviderLatency]
  |
  +-- cache.store() [virtual thread, fire-and-forget]
  +-- shadowTester.maybeShadow()      [virtual thread, sampled]
```

## Semantic Cache Math

Cosine similarity formula: dot product divided by product of magnitudes.

Default threshold is 0.95 which is very conservative. At 0.95 only near exact paraphrases match. Lowering to 0.85 allows broader semantic matches but increases false hit risk. Exact prompt SHA-256 shortcut bypasses the cosine scan for identical strings.

## Structured Concurrency Thread Model

`FallbackRouter.callWithTimeout()` uses `StructuredTaskScope.ShutdownOnFailure`:

- `scope.fork(provider::call)` spawns one virtual thread per call.
- `scope.joinUntil(Instant.now().plus(timeout))` blocks until the task completes or the deadline is reached.
- On `TimeoutException`: the scope cancels the forked task via thread interruption and throws a `ProviderException(status=0, cause=HttpTimeoutException)`.
- `throwIfFailed` re-raises any task exception. `ProviderException` instances pass through unchanged so timeout and retryable semantics stay intact.

The scope owns the virtual thread lifetime. When the scope closes, any running forked task gets interrupted. No thread leaks can occur.

## Lock-Free Token Accounting

`TenantBucket.tryConsume()` uses an atomic long compare-and-swap loop:

```
do {
    cur = available.get();
    if (cur < tokens) return false;
} while (!available.compareAndSet(cur, cur - tokens));
```

An atomic long is used instead of a long adder because the operation is conditional. We must read and decrement atomically. Long adders are optimised for unconditional increments and do not support conditional decrement.

## Overhead Budget

The on-path cost target is under 1ms:
- Rate limiter check: 1 CAS plus 1 map lookup under 0.1ms
- Circuit breaker state check: 1 volatile read in nanoseconds
- JFR event commit: no-op when no recording is active

Semantic cache lookup sits outside this budget because it performs SQLite input and output.
