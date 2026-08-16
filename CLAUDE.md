# SLOPWALL Guidelines
- **Stack:** Java 21+, Structured Concurrency, SQLite via JDBC, Testcontainers.
- **Build:** `./gradlew clean build`
- **Rules:** Explicit resource tracking. Lock-free counters (`LongAdder`). Max overhead < 1ms.
