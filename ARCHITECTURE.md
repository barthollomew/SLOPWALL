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

Cosine similarity: `sim(a,b) = dot(a,b) / (|a| * |b|)`

Default threshold is 0.95 (very conservative). At 0.95 only near-exact paraphrases
match; lowering to 0.85 allows broader semantic matches but increases false-hit risk.
Exact-prompt SHA-256 shortcut bypasses the cosine scan for identical strings.

## Structured Concurrency Thread Model

`FallbackRouter.callWithTimeout()` uses `StructuredTaskScope.ShutdownOnFailure`:

- `scope.fork(provider::call)` spawns one virtual thread per call.
- `scope.joinUntil(Instant.now().plus(timeout))` blocks until the task completes or
  the deadline is reached.
- On `TimeoutException`: the scope cancels the forked task via thread interruption and
  throws a `ProviderException(status=0, cause=HttpTimeoutException)`.
- `throwIfFailed` re-raises any task exception; `ProviderException` instances are passed
  through unchanged so `isTimeout()` and `isRetryable()` semantics are preserved.

The scope owns the virtual thread lifetime: when the scope closes, any still-running
forked task is interrupted. No thread leaks are possible.

## Lock-Free Token Accounting

`TenantBucket.tryConsume()` uses an `AtomicLong` CAS loop:

```
do {
    cur = available.get();
    if (cur < tokens) return false;
} while (!available.compareAndSet(cur, cur - tokens));
```

`AtomicLong` is used instead of `LongAdder` because the operation is conditional:
we must read and decrement atomically. `LongAdder` is optimised for unconditional
increments; it does not support conditional decrement.

## Overhead Budget

The on-path cost target is < 1ms:
- Rate limiter check: 1 CAS + 1 map lookup (< 0.1ms)
- Circuit breaker state check: 1 volatile read (nanoseconds)
- JFR event commit: no-op when no recording is active

Semantic cache lookup sits outside this budget; it has its own latency (SQLite I/O).
