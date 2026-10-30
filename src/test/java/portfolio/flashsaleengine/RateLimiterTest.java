package portfolio.flashsaleengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimiterTest {
    private static final long SECOND = 1_000_000_000L;

    @Test
    void admitsTheBurstThenRefuses() {
        AtomicLong now = new AtomicLong(0);
        RateLimiter r = new RateLimiter(10, 5, now::get);
        for (int i = 0; i < 5; i++) {
            assertTrue(r.tryAcquire(), "request " + i);
        }
        assertFalse(r.tryAcquire());
        assertEquals(SECOND / 10, r.nanosUntilNext());
    }

    @Test
    void refillsAtTheConfiguredRate() {
        AtomicLong now = new AtomicLong(0);
        RateLimiter r = new RateLimiter(10, 1, now::get);
        assertTrue(r.tryAcquire());
        assertFalse(r.tryAcquire());
        now.addAndGet(SECOND / 10 - 1);
        assertFalse(r.tryAcquire());
        now.addAndGet(1);
        assertTrue(r.tryAcquire());
        assertEquals(SECOND / 10, r.nanosUntilNext());
    }

    @Test
    void longRunAverageMatchesTheRate() {
        AtomicLong now = new AtomicLong(0);
        RateLimiter r = new RateLimiter(1_000, 50, now::get);
        int admitted = 0;
        for (int step = 0; step < 100_000; step++) {
            now.addAndGet(SECOND / 10_000);
            if (r.tryAcquire()) {
                admitted++;
            }
        }
        // 10 simulated seconds at 1,000 per second, plus at most the initial burst.
        assertTrue(admitted >= 10_000 && admitted <= 10_050, "admitted " + admitted);
    }

    @Test
    void idleTimeDoesNotGrowTheBurst() {
        AtomicLong now = new AtomicLong(0);
        RateLimiter r = new RateLimiter(100, 3, now::get);
        now.addAndGet(3_600 * SECOND);
        int admitted = 0;
        while (r.tryAcquire()) {
            admitted++;
        }
        assertEquals(3, admitted);
    }

    @Test
    void concurrentCallersNeverExceedTheBurst() throws InterruptedException {
        RateLimiter r = new RateLimiter(1e-3, 100, () -> 42L);
        AtomicInteger admitted = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 64; t++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < 50; i++) {
                    if (r.tryAcquire()) {
                        admitted.incrementAndGet();
                    }
                }
            }));
        }
        go.countDown();
        for (Thread t : threads) {
            t.join();
        }
        assertEquals(100, admitted.get());
    }

    @Test
    void rejectsBadSettings() {
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(Double.NaN, 1));
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(10, 0));
    }
}
