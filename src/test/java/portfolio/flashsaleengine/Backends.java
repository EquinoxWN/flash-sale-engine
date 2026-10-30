package portfolio.flashsaleengine;

import com.github.fppt.jedismock.RedisServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import redis.clients.jedis.RedisClient;

/**
 * Servers the tests run against. Locally: jedis-mock, an in-process Redis-protocol server that runs
 * Lua through LuaJ. In CI, {@code VALKEY_URL} also points at a real Valkey container, and every
 * parameterized test runs against both.
 */
final class Backends {
    private static final AtomicInteger SALES = new AtomicInteger();
    /** Client timeout used by the tests. */
    static final int TIMEOUT_MS = 30_000;

    private static RedisServer mock;

    private Backends() {}

    /** A named server URL. */
    record Backend(String name, URI uri) {
        @Override
        public String toString() {
            return name;
        }

        /** A pooled client with generous timeouts: jedis-mock is far slower than Valkey on a busy machine. */
        RedisClient client(int maxInFlight) {
            return Main.connect(uri, maxInFlight, TIMEOUT_MS);
        }
    }

    /** jedis-mock always, plus real Valkey when {@code VALKEY_URL} is set. */
    static List<Backend> all() {
        List<Backend> out = new ArrayList<>();
        out.add(new Backend("jedis-mock", mockUri()));
        String url = System.getenv("VALKEY_URL");
        if (url != null && !url.isBlank()) {
            out.add(new Backend("valkey", URI.create(url)));
        }
        return out;
    }

    /** URL of the shared in-process server, started on first use and stopped at JVM exit. */
    static synchronized URI mockUri() {
        if (mock == null) {
            try {
                mock = RedisServer.newRedisServer(0).start();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            RedisServer started = mock;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    started.stop();
                } catch (IOException e) {
                    // the JVM is exiting; nothing left to clean up
                }
            }));
        }
        return URI.create("redis://" + mock.getHost() + ":" + mock.getBindPort());
    }

    /** A sale id no other test uses. */
    static String freshSale(String prefix) {
        return prefix + "-" + SALES.incrementAndGet() + "-" + (System.nanoTime() % 100_000);
    }
}
