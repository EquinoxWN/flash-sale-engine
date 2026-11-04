package portfolio.flashsaleengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import redis.clients.jedis.RedisClient;

/** End to end: thousands of HTTP buyers through the gateway, then the oversell audit. */
class BurstTest {

    static List<Backends.Backend> backends() {
        return Backends.all();
    }

    @ParameterizedTest
    @MethodSource("backends")
    void fiveThousandBuyersSellOutExactlyWithoutOverselling(Backends.Backend b) throws Exception {
        try (RedisClient c = b.client(64)) {
            StockStore s = new StockStore(c);
            String sale = Backends.freshSale("burst");
            s.load(sale, new SaleConfig(500, 1_000, 1, 600), false);
            Burst.Result r;
            try (Gateway g = Gateway.start(s, new Gateway.Config("127.0.0.1", 0, 1e6, 100_000, 64, 1_000))) {
                r = Burst.run(URI.create("http://127.0.0.1:" + g.port()), sale, 5_000, 128, 3);
            }
            AuditReport a = s.audit(sale);
            assertTrue(a.ok(), a.violations().toString());
            assertEquals(0, a.oversold());
            assertEquals(500, r.won());
            assertEquals(500, a.winners());
            assertEquals(0, a.remaining());
            assertTrue(r.admitted() <= 1_000, "admitted " + r.admitted());
            assertEquals(0, r.errors());
        }
    }

    @Test
    void aTightRateLimitShedsBuyersButStillNeverOversells() throws Exception {
        try (RedisClient c = Backends.all().get(0).client(8)) {
            StockStore s = new StockStore(c);
            String sale = Backends.freshSale("shed");
            s.load(sale, new SaleConfig(50, 100, 1, 600), false);
            Burst.Result r;
            Gateway g = Gateway.start(s, new Gateway.Config("127.0.0.1", 0, 200, 40, 8, 50));
            try (g) {
                r = Burst.run(URI.create("http://127.0.0.1:" + g.port()), sale, 1_000, 64, 1);
            }
            AuditReport a = s.audit(sale);
            assertTrue(r.rateLimited() > 0, "rate limited " + r.rateLimited());
            assertEquals(g.shedByRateLimit(), r.rateLimited());
            assertTrue(a.ok(), a.violations().toString());
            assertEquals(r.won(), a.unitsInOrders());
            assertTrue(a.unitsInOrders() <= 50);
            assertEquals(1_000, r.won() + r.soldOut() + r.queueFull() + r.gaveUp() + r.errors());
        }
    }

    @Test
    void cliBurstPrintsTheAuditAndExitsZero() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(
                new String[] {
                    "burst",
                    "--valkey",
                    Backends.mockUri().toString(),
                    "--timeout-ms",
                    "30000",
                    "--sale",
                    Backends.freshSale("cli"),
                    "--units",
                    "200",
                    "--buyers",
                    "1000",
                    "--concurrency",
                    "64",
                    "--rate",
                    "1000000",
                    "--burst",
                    "100000",
                    "--attempts",
                    "20"
                },
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        String text = out.toString(StandardCharsets.UTF_8);
        assertEquals(0, code, text + err.toString(StandardCharsets.UTF_8));
        assertTrue(text.contains("won: 200"), text);
        assertTrue(text.contains("oversold=0 -> OK"), text);
    }

    @Test
    void onlyShedAnswersAreRetriedNotAStoreFailureWhoseOutcomeIsUnknown() throws Exception {
        AtomicInteger tokenCalls = new AtomicInteger();
        AtomicInteger buyCalls = new AtomicInteger();
        HttpServer fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        fake.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            boolean token = path.endsWith("/token");
            (token ? tokenCalls : buyCalls).incrementAndGet();
            // Token: shed once as busy, then issued. Buy: the store fails after the script may have run.
            boolean shed = token && tokenCalls.get() == 1;
            String body = token && !shed
                    ? "{\"token\":\"0123456789abcdef0123456789abcdef\"}"
                    : shed ? "{\"error\":\"busy\"}" : "{\"error\":\"store_unavailable\"}";
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(token && !shed ? 200 : 503, b.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(b);
            }
        });
        fake.start();
        try {
            Burst.Result r =
                    Burst.run(URI.create("http://127.0.0.1:" + fake.getAddress().getPort()), "s", 1, 1, 3);
            assertEquals(2, tokenCalls.get(), "a busy 503 is retried");
            assertEquals(1, buyCalls.get(), "a store_unavailable 503 is not retried");
            assertEquals(1, r.busy());
            assertEquals(1, r.errors());
            assertEquals(0, r.won());
        } finally {
            fake.stop(0);
        }
    }

    @Test
    void percentileUsesNearestRank() {
        long[] v = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        assertEquals(5, Burst.percentile(v, 0.5));
        assertEquals(10, Burst.percentile(v, 0.99));
        assertEquals(1, Burst.percentile(v, 0.0));
        assertEquals(0, Burst.percentile(new long[0], 0.5));
    }
}
