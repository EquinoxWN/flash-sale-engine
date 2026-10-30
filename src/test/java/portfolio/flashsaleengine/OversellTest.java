package portfolio.flashsaleengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import redis.clients.jedis.RedisClient;

/** Thousands of concurrent buyers against the atomic script: never one unit too many. */
class OversellTest {

    static List<Backends.Backend> backends() {
        return Backends.all();
    }

    @ParameterizedTest
    @MethodSource("backends")
    void twoThousandBuyersForOneHundredUnitsLeaveExactlyOneHundredWinners(Backends.Backend b) throws Exception {
        try (RedisClient c = b.client(64)) {
            // Open every pooled connection first: on JDK 21, 2,000 virtual threads hitting a cold pool wait in
            // commons-pool's synchronized create(), pin all carrier threads and stall until the pool timeout.
            c.getPool().addObjects(c.getPool().getMaxTotal());
            StockStore s = new StockStore(c);
            String sale = Backends.freshSale("race");
            s.load(sale, new SaleConfig(100, 2_000, 1, 600), false);
            int buyers = 2_000;
            AtomicLong won = new AtomicLong();
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> admits = new ArrayList<>();
                for (int i = 0; i < buyers; i++) {
                    String user = "u" + i;
                    String token = token(i);
                    admits.add(pool.submit(
                            () -> assertTrue(s.admit(sale, user, token).admitted())));
                }
                for (Future<?> f : admits) {
                    f.get(60, TimeUnit.SECONDS);
                }
                // Every buyer is parked on the latch, then all of them race for the stock at once.
                CountDownLatch go = new CountDownLatch(1);
                List<Future<?>> buys = new ArrayList<>();
                for (int i = 0; i < buyers; i++) {
                    String user = "u" + i;
                    String token = token(i);
                    buys.add(pool.submit(() -> {
                        assertTrue(go.await(60, TimeUnit.SECONDS));
                        if (s.buy(sale, user, token, 1).status() == Purchase.Status.WON) {
                            won.incrementAndGet();
                        }
                        return null;
                    }));
                }
                go.countDown();
                for (Future<?> f : buys) {
                    f.get(60, TimeUnit.SECONDS);
                }
            }
            AuditReport a = s.audit(sale);
            assertEquals(100, won.get());
            assertEquals(0, a.remaining());
            assertEquals(100, a.unitsInOrders());
            assertEquals(100, a.winners());
            assertEquals(0, a.oversold());
            assertTrue(a.ok(), a.violations().toString());
        }
    }

    @ParameterizedTest
    @MethodSource("backends")
    void randomizedSalesNeverOversell(Backends.Backend b) throws Exception {
        Random rnd = new Random(20261023L);
        try (RedisClient c = b.client(32)) {
            StockStore s = new StockStore(c);
            for (int trial = 0; trial < 25; trial++) {
                String sale = Backends.freshSale("rnd");
                int units = rnd.nextInt(60);
                int perUser = 1 + rnd.nextInt(4);
                int buyers = 1 + rnd.nextInt(200);
                long cap = 1 + rnd.nextInt(250);
                s.load(sale, new SaleConfig(units, cap, perUser, 600), false);
                long[] qty = new long[buyers];
                for (int i = 0; i < buyers; i++) {
                    qty[i] = 1 + rnd.nextInt(perUser + 1);
                }
                AtomicLong unitsWon = new AtomicLong();
                try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                    List<Future<?>> futures = new ArrayList<>();
                    for (int i = 0; i < buyers; i++) {
                        int n = i;
                        futures.add(pool.submit(() -> {
                            String user = "u" + (n % Math.max(1, buyers / 2));
                            Admission adm = s.admit(sale, user, token(n));
                            for (int attempt = 0; attempt < 2 && adm.admitted(); attempt++) {
                                int q = (int) Math.min(qty[n], 100);
                                if (s.buy(sale, user, adm.token(), q).status() == Purchase.Status.WON) {
                                    unitsWon.addAndGet(q);
                                }
                            }
                            return null;
                        }));
                    }
                    for (Future<?> f : futures) {
                        f.get(60, TimeUnit.SECONDS);
                    }
                }
                AuditReport a = s.audit(sale);
                String ctx = "trial " + trial + " units=" + units + " perUser=" + perUser + " cap=" + cap;
                assertTrue(a.ok(), ctx + " " + a.violations());
                assertEquals(0, a.oversold(), ctx);
                assertEquals(unitsWon.get(), a.unitsInOrders(), ctx);
                assertTrue(a.tokensIssued() <= cap, ctx);
            }
        }
    }

    /** Negative control: the same race with a read-then-write in Java oversells, and the audit sees it. */
    @Test
    void nonAtomicCheckThenDecrementOversellsAndTheAuditCatchesIt() throws Exception {
        try (RedisClient c = Backends.all().get(0).client(16)) {
            StockStore s = new StockStore(c);
            String sale = Backends.freshSale("naive");
            SaleKeys k = SaleKeys.of(sale);
            s.load(sale, new SaleConfig(1, 10, 1, 600), false);
            int buyers = 8;
            CyclicBarrier allRead = new CyclicBarrier(buyers);
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < buyers; i++) {
                    String user = "u" + i;
                    String token = token(i);
                    futures.add(pool.submit(() -> {
                        s.admit(sale, user, token);
                        long left = Long.parseLong(c.get(k.stock()));
                        allRead.await(30, TimeUnit.SECONDS);
                        if (left >= 1) {
                            c.decrBy(k.stock(), 1);
                            c.hincrBy(k.orders(), user, 1);
                        }
                        return null;
                    }));
                }
                for (Future<?> f : futures) {
                    f.get(60, TimeUnit.SECONDS);
                }
            }
            AuditReport a = s.audit(sale);
            assertFalse(a.ok());
            assertEquals(buyers - 1, a.oversold());
            assertEquals(-(buyers - 1), a.remaining());
        }
    }

    /** Deterministic 32-hex token for buyer {@code i}. */
    static String token(int i) {
        byte[] b = new byte[16];
        for (int j = 0; j < 4; j++) {
            b[j] = (byte) (i >>> (8 * j));
        }
        b[15] = 7;
        return HexFormat.of().formatHex(b);
    }
}
