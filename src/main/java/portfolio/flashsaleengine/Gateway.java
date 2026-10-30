package portfolio.flashsaleengine;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import redis.clients.jedis.exceptions.JedisConnectionException;

/**
 * HTTP front door of a sale. It sheds excess traffic before Valkey sees it (a rate limit, then a
 * cap on requests in flight), hands out capped queue tokens, and turns a token into a purchase
 * through the atomic buy script.
 *
 * <p>Routes: {@code GET /healthz}, {@code GET /sales/{id}}, {@code POST /sales/{id}/token}
 * and {@code POST /sales/{id}/buy?qty=N}. The buyer is named by the {@code X-User-Id} header,
 * which M1 trusts: an authenticating proxy must set it in a real deployment.
 */
public final class Gateway implements AutoCloseable {
    /** Header naming the buyer. */
    public static final String USER_HEADER = "X-User-Id";

    /** Header carrying the queue token on a purchase. */
    public static final String TOKEN_HEADER = "X-Queue-Token";

    private static final int MAX_PATH = 128;
    private static final int MAX_QUERY = 32;

    private final StockStore store;
    private final RateLimiter limiter;
    private final Semaphore inFlight;
    private final long inFlightWaitMillis;
    private final Supplier<String> tokens;
    private final HttpServer server;
    private final ExecutorService executor;
    private final AtomicLong shedRate = new AtomicLong();
    private final AtomicLong shedBusy = new AtomicLong();

    /**
     * Gateway settings.
     *
     * @param host address to bind; loopback by default so nothing is exposed unless asked
     * @param port port to bind, 0 for any free port
     * @param ratePerSecond average requests per second admitted to the sale routes
     * @param burst requests admitted at once above the average
     * @param maxInFlight most requests talking to Valkey at once
     * @param inFlightWaitMillis how long a request may wait for an in-flight slot before a 503
     */
    public record Config(
            String host, int port, double ratePerSecond, int burst, int maxInFlight, long inFlightWaitMillis) {
        /** Validates the bounds. */
        public Config {
            Objects.requireNonNull(host, "host");
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("port must be between 0 and 65535");
            }
            if (maxInFlight < 1 || maxInFlight > 10_000) {
                throw new IllegalArgumentException("max in flight must be between 1 and 10000");
            }
            if (inFlightWaitMillis < 0 || inFlightWaitMillis > 10_000) {
                throw new IllegalArgumentException("in-flight wait must be between 0 and 10000 ms");
            }
        }

        /** Loopback, any free port, 5,000 requests per second, bursts of 1,000, 64 in flight. */
        public static Config defaults() {
            return new Config("127.0.0.1", 0, 5_000, 1_000, 64, 100);
        }
    }

    /** Starts a gateway with random queue tokens from {@link SecureRandom}. */
    public static Gateway start(StockStore store, Config config) throws IOException {
        SecureRandom random = new SecureRandom();
        return start(store, config, () -> {
            byte[] b = new byte[16];
            random.nextBytes(b);
            return HexFormat.of().formatHex(b);
        });
    }

    /** Starts a gateway with the given token source (tests pass a deterministic one). */
    public static Gateway start(StockStore store, Config config, Supplier<String> tokenSource) throws IOException {
        return new Gateway(store, config, tokenSource);
    }

    private Gateway(StockStore store, Config config, Supplier<String> tokenSource) throws IOException {
        this.store = Objects.requireNonNull(store, "store");
        this.limiter = new RateLimiter(config.ratePerSecond(), config.burst());
        this.inFlight = new Semaphore(config.maxInFlight());
        this.inFlightWaitMillis = config.inFlightWaitMillis();
        this.tokens = Objects.requireNonNull(tokenSource, "tokenSource");
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.server = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 4096);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    /** Port the gateway listens on. */
    public int port() {
        return server.getAddress().getPort();
    }

    /** Requests refused by the rate limit so far. */
    public long shedByRateLimit() {
        return shedRate.get();
    }

    /** Requests refused because every in-flight slot stayed busy. */
    public long shedByBusy() {
        return shedBusy.get();
    }

    /** Stops accepting requests and finishes those in progress. */
    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }

    /** Routes one request and always sends exactly one response. */
    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            Reply reply;
            try {
                reply = hasBody(ex) ? new Reply(413, json("error", "body_not_accepted")) : route(ex);
            } catch (JedisConnectionException e) {
                reply = new Reply(503, json("error", "store_unavailable"));
            } catch (RuntimeException e) {
                reply = new Reply(500, json("error", "internal"));
            }
            send(ex, reply);
        }
    }

    /** Picks the handler for the method and path. */
    private Reply route(HttpExchange ex) {
        String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
        String path = ex.getRequestURI().getRawPath();
        if (path == null || path.length() > MAX_PATH) {
            return new Reply(404, json("error", "not_found"));
        }
        if (path.equals("/healthz")) {
            if (!method.equals("GET")) {
                return new Reply(405, json("error", "method_not_allowed"));
            }
            return withSlot(() -> store.ping()
                    ? new Reply(200, json("status", "ok"))
                    : new Reply(503, json("status", "store_unavailable")));
        }
        String[] parts = path.split("/", -1);
        if (parts.length < 3 || parts.length > 4 || !parts[0].isEmpty() || !parts[1].equals("sales")) {
            return new Reply(404, json("error", "not_found"));
        }
        String sale = parts[2];
        if (!Ids.isSaleId(sale)) {
            return new Reply(400, json("error", "bad_sale_id"));
        }
        String action = parts.length == 4 ? parts[3] : "";
        String expected = action.isEmpty() ? "GET" : "POST";
        if (!action.isEmpty() && !action.equals("token") && !action.equals("buy")) {
            return new Reply(404, json("error", "not_found"));
        }
        if (!method.equals(expected)) {
            return new Reply(405, json("error", "method_not_allowed"));
        }
        if (!limiter.tryAcquire()) {
            shedRate.incrementAndGet();
            return new Reply(429, json("error", "rate_limited"));
        }
        return switch (action) {
            case "" -> withSlot(() -> status(sale));
            case "token" -> token(ex, sale);
            default -> buy(ex, sale);
        };
    }

    /** {@code GET /sales/{id}}: counters of the sale. */
    private Reply status(String sale) {
        SaleStatus s = store.status(sale);
        if (!s.loaded()) {
            return new Reply(404, json("error", "not_loaded"));
        }
        return new Reply(
                200,
                "{\"sale\":\"" + sale + "\",\"units\":" + s.units() + ",\"remaining\":" + s.remaining()
                        + ",\"sold\":" + s.sold() + ",\"tokensIssued\":" + s.tokensIssued()
                        + ",\"tokenCap\":" + s.tokenCap() + "}");
    }

    /** {@code POST /sales/{id}/token}: a queue token for the user, or why not. */
    private Reply token(HttpExchange ex, String sale) {
        String user = ex.getRequestHeaders().getFirst(USER_HEADER);
        if (!Ids.isUserId(user)) {
            return new Reply(400, json("error", "bad_user_id"));
        }
        String candidate = tokens.get();
        return withSlot(() -> {
            Admission a = store.admit(sale, user, candidate);
            return switch (a.status()) {
                case ISSUED, ALREADY_ISSUED -> new Reply(200, "{\"token\":\"" + a.token() + "\"}");
                case SOLD_OUT -> new Reply(410, json("error", "sold_out"));
                case QUEUE_FULL -> new Reply(429, json("error", "queue_full"));
                case NOT_LOADED -> new Reply(404, json("error", "not_loaded"));
            };
        });
    }

    /** {@code POST /sales/{id}/buy}: the atomic purchase. */
    private Reply buy(HttpExchange ex, String sale) {
        String user = ex.getRequestHeaders().getFirst(USER_HEADER);
        if (!Ids.isUserId(user)) {
            return new Reply(400, json("error", "bad_user_id"));
        }
        String token = ex.getRequestHeaders().getFirst(TOKEN_HEADER);
        if (!Ids.isToken(token)) {
            return new Reply(403, json("error", "bad_token"));
        }
        int qty = quantity(ex.getRequestURI().getRawQuery());
        if (qty < 1) {
            return new Reply(400, json("error", "bad_quantity"));
        }
        return withSlot(() -> {
            Purchase p = store.buy(sale, user, token, qty);
            return switch (p.status()) {
                case WON -> new Reply(200, "{\"result\":\"won\",\"remaining\":" + p.remaining() + "}");
                case SOLD_OUT -> new Reply(410, json("error", "sold_out"));
                case INSUFFICIENT_STOCK ->
                    new Reply(409, "{\"error\":\"insufficient_stock\",\"remaining\":" + p.remaining() + "}");
                case LIMIT_REACHED -> new Reply(409, json("error", "limit_reached"));
                case BAD_TOKEN -> new Reply(403, json("error", "bad_token"));
                case NOT_LOADED -> new Reply(404, json("error", "not_loaded"));
            };
        });
    }

    /** Runs a Valkey call inside an in-flight slot, or sheds with 503 when none frees up in time. */
    private Reply withSlot(Supplier<Reply> call) {
        boolean acquired;
        try {
            acquired = inFlight.tryAcquire(inFlightWaitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Reply(503, json("error", "busy"));
        }
        if (!acquired) {
            shedBusy.incrementAndGet();
            return new Reply(503, json("error", "busy"));
        }
        try {
            return call.get();
        } finally {
            inFlight.release();
        }
    }

    /** Quantity from {@code qty=N} (1 to 100), 1 when absent, -1 when malformed. */
    static int quantity(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return 1;
        }
        if (rawQuery.length() > MAX_QUERY || !rawQuery.startsWith("qty=") || rawQuery.indexOf('&') >= 0) {
            return -1;
        }
        String v = rawQuery.substring(4);
        if (v.isEmpty() || v.length() > 3 || !v.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return -1;
        }
        int q = Integer.parseInt(v);
        return q >= 1 && q <= 100 ? q : -1;
    }

    /** One-field JSON object; both strings are constants of this class. */
    private static String json(String key, String value) {
        return "{\"" + key + "\":\"" + value + "\"}";
    }

    /** True when the request announces a body; no route accepts one. */
    private static boolean hasBody(HttpExchange ex) {
        String length = ex.getRequestHeaders().getFirst("Content-Length");
        return ex.getRequestHeaders().containsKey("Transfer-Encoding")
                || (length != null && !length.trim().equals("0"));
    }

    /** Writes the status, a JSON body and, for shedding statuses, a Retry-After hint. */
    private static void send(HttpExchange ex, Reply reply) throws IOException {
        byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        if (reply.status() == 429 || reply.status() == 503) {
            ex.getResponseHeaders().set("Retry-After", "1");
        }
        ex.sendResponseHeaders(reply.status(), body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    /** Status code and JSON body. */
    private record Reply(int status, String body) {}
}
