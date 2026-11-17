package com.heonezen.raytrace.engine;

// A time budget shared by every player one server thread ticks: a token bucket refilled with wall-clock time.
final class ScanBudget {

    private static final long TICK_NANOS = 50_000_000L;

    private static final class Bucket {
        long refilledAt = System.nanoTime();
        long tokens;
    }

    private final ThreadLocal<Bucket> buckets = ThreadLocal.withInitial(Bucket::new);

    // The nanoseconds this thread may still spend in the current tick.
    long available(long perTickNanos) {
        Bucket bucket = buckets.get();
        long now = System.nanoTime();
        long elapsed = now - bucket.refilledAt;
        if (elapsed > 0) {
            // Multiply in double: elapsed * perTick could overflow a long after a long pause.
            double refill = (double) elapsed * perTickNanos / TICK_NANOS;
            bucket.tokens = (long) Math.min(perTickNanos, bucket.tokens + refill);
            bucket.refilledAt = now;
        }
        return bucket.tokens;
    }

    // Takes the time a scan actually used from this thread's bucket.
    void spend(long nanos) {
        if (nanos > 0) {
            buckets.get().tokens -= nanos;
        }
    }
}
