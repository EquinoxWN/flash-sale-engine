package portfolio.flashsaleengine;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Token bucket in its GCRA form: one atomic "theoretical arrival time" instead of a token count
 * and a refill timestamp, so it needs no lock and no background refill thread.
 */
public final class RateLimiter {
    private final long intervalNanos;
    private final long toleranceNanos;
    private final LongSupplier clock;
    private final AtomicLong tat;

    /** Allows {@code perSecond} requests per second on average and bursts of up to {@code burst}. */
    public RateLimiter(double perSecond, int burst, LongSupplier nanoClock) {
        if (!(perSecond > 0) || perSecond > 1e9) {
            throw new IllegalArgumentException("rate must be between 0 and 1e9 per second");
        }
        if (burst < 1) {
            throw new IllegalArgumentException("burst must be at least 1");
        }
        this.intervalNanos = Math.max(1, Math.round(1e9 / perSecond));
        this.toleranceNanos = intervalNanos * (burst - 1L);
        this.clock = nanoClock;
        this.tat = new AtomicLong(nanoClock.getAsLong());
    }

    /** Limiter on the system's monotonic clock. */
    public RateLimiter(double perSecond, int burst) {
        this(perSecond, burst, System::nanoTime);
    }

    /** Takes one token if available; never blocks. */
    public boolean tryAcquire() {
        while (true) {
            long now = clock.getAsLong();
            long current = tat.get();
            long start = Math.max(current, now);
            if (start - now > toleranceNanos) {
                return false;
            }
            if (tat.compareAndSet(current, start + intervalNanos)) {
                return true;
            }
        }
    }

    /** Nanoseconds until the next token is available, zero if one is available now. */
    public long nanosUntilNext() {
        long wait = tat.get() - toleranceNanos - clock.getAsLong();
        return Math.max(0, wait);
    }
}
