package portfolio.flashsaleengine;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import redis.clients.jedis.ConnectionPoolConfig;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.exceptions.JedisException;

/** Command-line entry point: load, status, audit, serve and burst. */
public final class Main {
    private static final String USAGE = String.join(
            "\n",
            "usage: flash-sale <command> [--valkey redis://host:port] [--timeout-ms 2000] [options]",
            "  load   --sale ID --units N [--token-cap N] [--per-user N] [--ttl SECONDS] [--force]",
            "  status --sale ID",
            "  audit  --sale ID                       exit 1 if any unit was oversold",
            "  serve  [--bind 127.0.0.1] [--port 8080] [--rate 5000] [--burst 1000] [--max-in-flight 64]",
            "  burst  --sale ID --units N --buyers N [--concurrency 256] [--attempts 3]",
            "         [--token-cap N] [--rate 5000] [--burst 1000] [--max-in-flight 64]",
            "The Valkey URL comes from --valkey, else VALKEY_URL, else redis://127.0.0.1:6379.");

    private static final Map<String, Set<String>> OPTIONS = Map.of(
            "load", Set.of("valkey", "timeout-ms", "sale", "units", "token-cap", "per-user", "ttl", "force"),
            "status", Set.of("valkey", "timeout-ms", "sale"),
            "audit", Set.of("valkey", "timeout-ms", "sale"),
            "serve", Set.of("valkey", "timeout-ms", "bind", "port", "rate", "burst", "max-in-flight"),
            "burst",
                    Set.of(
                            "valkey",
                            "timeout-ms",
                            "sale",
                            "units",
                            "buyers",
                            "concurrency",
                            "attempts",
                            "token-cap",
                            "rate",
                            "burst",
                            "max-in-flight"));

    private Main() {}

    /** Runs the command and exits with its code. */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /** Runs one command; returns 0 on success, 1 on a failed check, 2 on bad usage or I/O errors. */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || !OPTIONS.containsKey(args[0])) {
            err.println(USAGE);
            return 2;
        }
        String command = args[0];
        Map<String, String> opts;
        try {
            opts = parse(args, OPTIONS.get(command));
        } catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            err.println(USAGE);
            return 2;
        }
        int inFlight;
        int timeout;
        try {
            inFlight = intOpt(opts, "max-in-flight", 64, 1, 10_000);
            timeout = intOpt(opts, "timeout-ms", 2_000, 100, 60_000);
        } catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            return 2;
        }
        try (RedisClient valkey = connect(valkeyUrl(opts), inFlight, timeout)) {
            StockStore store = new StockStore(valkey);
            return switch (command) {
                case "load" -> load(store, opts, out);
                case "status" -> status(store, opts, out);
                case "audit" -> audit(store, opts, out);
                case "serve" -> serve(store, opts, inFlight, out);
                default -> burst(store, opts, inFlight, out);
            };
        } catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            return 2;
        } catch (JedisException | IOException e) {
            err.println("error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return 2;
        }
    }

    /** {@code load}: puts the sale's stock into Valkey before it opens. */
    private static int load(StockStore store, Map<String, String> o, PrintStream out) {
        String sale = Ids.requireSaleId(o.get("sale"));
        SaleConfig cfg = config(o);
        boolean loaded = store.load(sale, cfg, o.containsKey("force"));
        if (!loaded) {
            out.println("sale " + sale + " is already loaded; pass --force to reset it");
            return 1;
        }
        out.printf(
                "loaded sale %s: %d units, %d queue tokens, %d per user, ttl %d s%n",
                sale, cfg.units(), cfg.tokenCap(), cfg.perUserLimit(), cfg.ttlSeconds());
        return 0;
    }

    /** {@code status}: prints the sale's counters. */
    private static int status(StockStore store, Map<String, String> o, PrintStream out) {
        String sale = Ids.requireSaleId(o.get("sale"));
        SaleStatus s = store.status(sale);
        if (!s.loaded()) {
            out.println("sale " + sale + " is not loaded");
            return 1;
        }
        out.printf(
                "sale %s: units=%d remaining=%d sold=%d tokens=%d/%d per_user=%d%n",
                sale, s.units(), s.remaining(), s.sold(), s.tokensIssued(), s.tokenCap(), s.perUserLimit());
        return 0;
    }

    /** {@code audit}: the oversell check; exit 1 on any violation. */
    private static int audit(StockStore store, Map<String, String> o, PrintStream out) {
        String sale = Ids.requireSaleId(o.get("sale"));
        AuditReport a = store.audit(sale);
        out.printf(
                "audit %s: units=%d sold=%d in_orders=%d remaining=%d winners=%d oversold=%d%n",
                sale, a.units(), a.sold(), a.unitsInOrders(), a.remaining(), a.winners(), a.oversold());
        for (String v : a.violations()) {
            out.println("violation: " + v);
        }
        out.println(a.ok() ? "audit OK" : "audit FAILED");
        return a.ok() ? 0 : 1;
    }

    /** {@code serve}: runs the gateway until the process is stopped. */
    private static int serve(StockStore store, Map<String, String> o, int inFlight, PrintStream out)
            throws IOException {
        Gateway.Config cfg = new Gateway.Config(
                o.getOrDefault("bind", "127.0.0.1"),
                intOpt(o, "port", 8080, 0, 65_535),
                intOpt(o, "rate", 5_000, 1, 1_000_000),
                intOpt(o, "burst", 1_000, 1, 1_000_000),
                inFlight,
                100);
        CountDownLatch stop = new CountDownLatch(1);
        try (Gateway g = Gateway.start(store, cfg)) {
            Runtime.getRuntime().addShutdownHook(new Thread(stop::countDown));
            out.println("gateway listening on " + cfg.host() + ":" + g.port());
            stop.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }

    /** {@code burst}: loads a sale, fires buyers at an in-process gateway, then audits. */
    private static int burst(StockStore store, Map<String, String> o, int inFlight, PrintStream out)
            throws IOException {
        String sale = Ids.requireSaleId(o.get("sale"));
        SaleConfig cfg = config(o);
        int buyers = intOpt(o, "buyers", 0, 1, 1_000_000);
        int concurrency = intOpt(o, "concurrency", 256, 1, 10_000);
        int attempts = intOpt(o, "attempts", 3, 1, 100);
        int rate = intOpt(o, "rate", 5_000, 1, 1_000_000);
        int burst = intOpt(o, "burst", 1_000, 1, 1_000_000);
        store.load(sale, cfg, true);
        out.printf(
                "burst: sale=%s units=%d buyers=%d concurrency=%d rate=%d/s burst=%d token_cap=%d in_flight=%d%n",
                sale, cfg.units(), buyers, concurrency, rate, burst, cfg.tokenCap(), inFlight);
        Burst.Result r;
        try (Gateway g = Gateway.start(store, new Gateway.Config("127.0.0.1", 0, rate, burst, inFlight, 100))) {
            r = Burst.run(URI.create("http://127.0.0.1:" + g.port()), sale, buyers, concurrency, attempts);
        }
        AuditReport a = store.audit(sale);
        List<String> lines = Burst.describe(r, a);
        lines.forEach(out::println);
        if ("true".equals(System.getenv("GITHUB_ACTIONS"))) {
            out.println((a.ok() ? "::notice title=burst::" : "::error title=burst::") + lines.get(lines.size() - 1));
        }
        return a.ok() && r.won() == a.winners() ? 0 : 1;
    }

    /** Sale settings from the options; the token cap defaults to twice the units. */
    private static SaleConfig config(Map<String, String> o) {
        long units = longOpt(o, "units", -1, 0, SaleConfig.MAX_UNITS);
        return new SaleConfig(
                units,
                longOpt(o, "token-cap", Math.max(1, units * 2), 1, SaleConfig.MAX_TOKENS),
                intOpt(o, "per-user", 1, 1, 100),
                longOpt(o, "ttl", 24 * 3600, 1, 30L * 24 * 3600));
    }

    /** Valkey URL from the option, the environment or the local default. */
    private static URI valkeyUrl(Map<String, String> o) {
        String url = o.getOrDefault("valkey", System.getenv().getOrDefault("VALKEY_URL", "redis://127.0.0.1:6379"));
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("valkey URL is not a valid URI");
        }
        if (!"redis".equals(uri.getScheme())) {
            throw new IllegalArgumentException("valkey URL must start with redis:// (TLS is not supported in M1)");
        }
        return uri;
    }

    /** A pooled client with 2-second timeouts, sized for the gateway's in-flight cap. */
    static RedisClient connect(URI uri, int maxInFlight) {
        return connect(uri, maxInFlight, 2_000);
    }

    /** A pooled client with the given connect, read and pool-wait timeout. */
    static RedisClient connect(URI uri, int maxInFlight, int timeoutMillis) {
        ConnectionPoolConfig pool = new ConnectionPoolConfig();
        pool.setMaxTotal(maxInFlight + 2);
        pool.setMaxIdle(maxInFlight + 2);
        pool.setMaxWait(Duration.ofMillis(timeoutMillis));
        DefaultJedisClientConfig.Builder cfg = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(timeoutMillis)
                .socketTimeoutMillis(timeoutMillis)
                .clientName("flash-sale-engine");
        String info = uri.getRawUserInfo();
        if (info != null) {
            int colon = info.indexOf(':');
            String user = URLDecoder.decode(colon < 0 ? info : info.substring(0, colon), StandardCharsets.UTF_8);
            if (colon >= 0) {
                cfg.password(URLDecoder.decode(info.substring(colon + 1), StandardCharsets.UTF_8));
            }
            if (!user.isEmpty()) {
                cfg.user(user);
            }
        }
        String path = uri.getPath();
        if (path != null && path.length() > 1) {
            try {
                cfg.database(Integer.parseInt(path.substring(1)));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("valkey URL path must be a database number");
            }
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("valkey URL needs a host");
        }
        HostAndPort at = new HostAndPort(uri.getHost(), uri.getPort() < 0 ? 6379 : uri.getPort());
        return RedisClient.builder()
                .hostAndPort(at)
                .clientConfig(cfg.build())
                .poolConfig(pool)
                .build();
    }

    /** {@code --key value} pairs ({@code --force} takes no value), restricted to the allowed keys. */
    static Map<String, String> parse(String[] args, Set<String> allowed) {
        Map<String, String> out = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--") || !allowed.contains(a.substring(2))) {
                throw new IllegalArgumentException("unknown option " + a);
            }
            String key = a.substring(2);
            if (key.equals("force")) {
                out.put(key, "true");
                continue;
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("option " + a + " needs a value");
            }
            out.put(key, args[++i]);
        }
        return out;
    }

    /** Integer option within bounds; {@code def} below {@code min} makes it required. */
    private static int intOpt(Map<String, String> o, String key, int def, int min, int max) {
        return (int) longOpt(o, key, def, min, max);
    }

    /** Long option within bounds; {@code def} below {@code min} makes it required. */
    private static long longOpt(Map<String, String> o, String key, long def, long min, long max) {
        String v = o.get(key);
        if (v == null) {
            if (def < min) {
                throw new IllegalArgumentException("--" + key + " is required");
            }
            return def;
        }
        long n;
        try {
            n = Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + key + " must be a whole number");
        }
        if (n < min || n > max) {
            throw new IllegalArgumentException("--" + key + " must be between " + min + " and " + max);
        }
        return n;
    }
}
