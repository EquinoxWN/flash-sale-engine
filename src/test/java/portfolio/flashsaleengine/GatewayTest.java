package portfolio.flashsaleengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import redis.clients.jedis.RedisClient;

/** The HTTP gateway end to end against the in-process server. */
class GatewayTest {
    private static RedisClient client;
    private static StockStore store;
    private static HttpClient http;

    @BeforeAll
    static void connect() {
        client = Main.connect(Backends.mockUri(), 16, Backends.TIMEOUT_MS);
        store = new StockStore(client);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterAll
    static void close() {
        http.close();
        client.close();
    }

    @Test
    void tokenThenBuyWinsAndStatusShowsTheSale() throws Exception {
        String sale = Backends.freshSale("gw");
        store.load(sale, new SaleConfig(2, 4, 1, 600), false);
        try (Gateway g = Gateway.start(store, Gateway.Config.defaults())) {
            HttpResponse<String> t = post(g, "/sales/" + sale + "/token", "alice", null);
            assertEquals(200, t.statusCode());
            String token = t.body().replaceAll(".*\"token\":\"([0-9a-f]{32})\".*", "$1");
            assertTrue(Ids.isToken(token), t.body());

            HttpResponse<String> again = post(g, "/sales/" + sale + "/token", "alice", null);
            assertEquals(t.body(), again.body());

            HttpResponse<String> buy = post(g, "/sales/" + sale + "/buy", "alice", token);
            assertEquals(200, buy.statusCode());
            assertEquals("{\"result\":\"won\",\"remaining\":1}", buy.body());
            assertEquals("no-store", buy.headers().firstValue("Cache-Control").orElse(""));

            HttpResponse<String> twice = post(g, "/sales/" + sale + "/buy", "alice", token);
            assertEquals(409, twice.statusCode());
            assertEquals("{\"error\":\"limit_reached\"}", twice.body());

            HttpResponse<String> st = get(g, "/sales/" + sale);
            assertEquals(200, st.statusCode());
            assertEquals(
                    "{\"sale\":\"" + sale
                            + "\",\"units\":2,\"remaining\":1,\"sold\":1,\"tokensIssued\":1,\"tokenCap\":4}",
                    st.body());
            assertEquals(200, get(g, "/healthz").statusCode());
        }
    }

    @Test
    void soldOutAndQueueFullAreDistinctAnswers() throws Exception {
        String sale = Backends.freshSale("gw-out");
        store.load(sale, new SaleConfig(1, 2, 1, 600), false);
        try (Gateway g = Gateway.start(store, Gateway.Config.defaults())) {
            String a = tokenOf(post(g, "/sales/" + sale + "/token", "a", null));
            String b = tokenOf(post(g, "/sales/" + sale + "/token", "b", null));
            HttpResponse<String> full = post(g, "/sales/" + sale + "/token", "c", null);
            assertEquals(429, full.statusCode());
            assertEquals("{\"error\":\"queue_full\"}", full.body());
            assertEquals("1", full.headers().firstValue("Retry-After").orElse(""));
            assertEquals(200, post(g, "/sales/" + sale + "/buy", "a", a).statusCode());
            HttpResponse<String> late = post(g, "/sales/" + sale + "/buy", "b", b);
            assertEquals(410, late.statusCode());
            assertEquals(410, post(g, "/sales/" + sale + "/token", "d", null).statusCode());
        }
    }

    @ParameterizedTest
    @CsvSource({
        "POST, /sales/BAD/token, alice, , 400, bad_sale_id",
        "POST, /sales/s1/token, a b, , 400, bad_user_id",
        "POST, /sales/s1/buy, alice, , 403, bad_token",
        "POST, /sales/s1/buy, alice, ABC, 403, bad_token",
        "POST, /sales/s1/buy?qty=0, alice, 0123456789abcdef0123456789abcdef, 400, bad_quantity",
        "POST, /sales/s1/buy, alice, 0123456789abcdef0123456789abcdef, 403, bad_token",
        "GET, /sales/s1/token, alice, , 405, method_not_allowed",
        "POST, /sales/s1, alice, , 405, method_not_allowed",
        "POST, /healthz, alice, , 405, method_not_allowed",
        "GET, /sales/s1/refund, alice, , 404, not_found",
        "GET, /admin, alice, , 404, not_found",
        "GET, /sales/s1/token/x, alice, , 404, not_found",
        "GET, /sales/never-loaded, alice, , 404, not_loaded",
    })
    void rejectsBadRequestsWithAReason(String method, String path, String user, String token, int status, String error)
            throws Exception {
        String sale = "s1";
        store.load(sale, new SaleConfig(5, 5, 1, 600), true);
        try (Gateway g = Gateway.start(store, Gateway.Config.defaults())) {
            HttpRequest.Builder rb = HttpRequest.newBuilder(uri(g, path));
            if (!user.contains(" ")) {
                rb.header(Gateway.USER_HEADER, user);
            }
            if (token != null) {
                rb.header(Gateway.TOKEN_HEADER, token);
            }
            rb.method(method, HttpRequest.BodyPublishers.noBody());
            HttpResponse<String> r = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(status, r.statusCode(), r.body());
            assertTrue(r.body().contains("\"" + error + "\""), r.body());
        }
    }

    @Test
    void refusesRequestBodies() throws Exception {
        try (Gateway g = Gateway.start(store, Gateway.Config.defaults())) {
            HttpRequest req = HttpRequest.newBuilder(uri(g, "/sales/s1/token"))
                    .header(Gateway.USER_HEADER, "alice")
                    .POST(HttpRequest.BodyPublishers.ofString("x".repeat(1_000)))
                    .build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            assertEquals(413, r.statusCode());
        }
    }

    @Test
    void rateLimitShedsTheExcessBeforeValkey() throws Exception {
        String sale = Backends.freshSale("gw-rate");
        store.load(sale, new SaleConfig(100, 200, 1, 600), false);
        Gateway.Config cfg = new Gateway.Config("127.0.0.1", 0, 0.001, 5, 16, 100);
        try (Gateway g = Gateway.start(store, cfg)) {
            int ok = 0;
            int limited = 0;
            for (int i = 0; i < 12; i++) {
                HttpResponse<String> r = post(g, "/sales/" + sale + "/token", "u" + i, null);
                if (r.statusCode() == 200) {
                    ok++;
                } else if (r.statusCode() == 429 && r.body().contains("rate_limited")) {
                    limited++;
                }
            }
            assertEquals(5, ok);
            assertEquals(7, limited);
            assertEquals(7, g.shedByRateLimit());
            assertEquals(5, store.status(sale).tokensIssued());
        }
    }

    /** A store that accepts connections and never answers: in-flight slots stay busy until timeout. */
    @Test
    @SuppressWarnings("try")
    void busyInFlightSlotsShedWith503AndADeadStoreAnswers503() throws Exception {
        try (ServerSocket silent = new ServerSocket(0);
                RedisClient stuck = Main.connect(URI.create("redis://127.0.0.1:" + silent.getLocalPort()), 1)) {
            List<Socket> accepted = new ArrayList<>();
            Thread acceptor = Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        accepted.add(silent.accept());
                    }
                } catch (IOException e) {
                    // closed at the end of the test
                }
            });
            Gateway.Config cfg = new Gateway.Config("127.0.0.1", 0, 1_000, 100, 1, 0);
            try (Gateway g = Gateway.start(new StockStore(stuck), cfg)) {
                // Whichever request takes the only slot hangs on the silent store; the others are shed at once.
                List<CompletableFuture<HttpResponse<String>>> calls = new ArrayList<>();
                for (int i = 0; i < 20; i++) {
                    calls.add(http.sendAsync(
                            request(g, "/sales/s1/token", "u" + i, null), HttpResponse.BodyHandlers.ofString()));
                }
                int busy = 0;
                int unavailable = 0;
                for (CompletableFuture<HttpResponse<String>> f : calls) {
                    HttpResponse<String> r = f.get();
                    assertEquals(503, r.statusCode(), r.body());
                    if (r.body().equals("{\"error\":\"busy\"}")) {
                        busy++;
                    } else if (r.body().equals("{\"error\":\"store_unavailable\"}")) {
                        unavailable++;
                    }
                }
                assertTrue(busy >= 1, "busy " + busy);
                assertTrue(unavailable >= 1, "unavailable " + unavailable);
                assertEquals(20, busy + unavailable);
                assertEquals(busy, g.shedByBusy());
            }
            silent.close();
            acceptor.join();
            for (Socket s : accepted) {
                s.close();
            }
        }
    }

    @Test
    void quantityParserAcceptsOnlyOneSmallNumber() {
        assertEquals(1, Gateway.quantity(null));
        assertEquals(1, Gateway.quantity(""));
        assertEquals(3, Gateway.quantity("qty=3"));
        assertEquals(100, Gateway.quantity("qty=100"));
        assertEquals(-1, Gateway.quantity("qty=101"));
        assertEquals(-1, Gateway.quantity("qty=-1"));
        assertEquals(-1, Gateway.quantity("qty="));
        assertEquals(-1, Gateway.quantity("qty=1&qty=2"));
        assertEquals(-1, Gateway.quantity("n=1"));
        assertEquals(-1, Gateway.quantity("qty=99999999999"));
    }

    /** Token from a 200 answer. */
    private static String tokenOf(HttpResponse<String> r) {
        assertEquals(200, r.statusCode(), r.body());
        return r.body().replaceAll(".*\"token\":\"([0-9a-f]{32})\".*", "$1");
    }

    private static URI uri(Gateway g, String path) {
        return URI.create("http://127.0.0.1:" + g.port() + path);
    }

    private static HttpRequest request(Gateway g, String path, String user, String token) {
        HttpRequest.Builder rb = HttpRequest.newBuilder(uri(g, path))
                .header(Gateway.USER_HEADER, user)
                .POST(HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            rb.header(Gateway.TOKEN_HEADER, token);
        }
        return rb.build();
    }

    private static HttpResponse<String> post(Gateway g, String path, String user, String token) throws Exception {
        return http.send(request(g, path, user, token), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(Gateway g, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(g, path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
