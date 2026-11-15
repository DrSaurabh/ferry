package app.ferry.core

// Ph2a acceptance (plan §6): deliberate API-26+ reference; ApiLevelScanTest must fail. Removed in the next commit.
internal fun deliberateApi26(): Long = java.time.Instant.now().toEpochMilli()
