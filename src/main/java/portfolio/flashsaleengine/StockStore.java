package portfolio.flashsaleengine;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import redis.clients.jedis.UnifiedJedis;

/**
 * Stock of every sale, kept in Valkey. Each operation is one Lua script, so each check and its
 * write happen in one atomic step on the server: concurrent buyers cannot interleave between them.
 */
public final class StockStore {
    private static final LuaScript LOAD = LuaScript.load("load");
    private static final LuaScript ADMIT = LuaScript.load("admit");
    private static final LuaScript BUY = LuaScript.load("buy");
    private static final LuaScript STATUS = LuaScript.load("status");
    private static final LuaScript AUDIT = LuaScript.load("audit");

    private final UnifiedJedis valkey;

    /** Store backed by a Valkey (or Redis-protocol) client; the caller owns and closes it. */
    public StockStore(UnifiedJedis valkey) {
        this.valkey = Objects.requireNonNull(valkey, "valkey");
    }

    /** The five scripts, for tests and for preloading with SCRIPT LOAD. */
    public static List<LuaScript> scripts() {
        return List.of(LOAD, ADMIT, BUY, STATUS, AUDIT);
    }

    /** Loads stock before the sale; returns false when it is already loaded and force is off. */
    public boolean load(String saleId, SaleConfig config, boolean force) {
        Object r = LOAD.run(
                valkey,
                SaleKeys.of(saleId).all(),
                List.of(
                        Long.toString(config.units()),
                        Long.toString(config.tokenCap()),
                        Integer.toString(config.perUserLimit()),
                        Long.toString(config.ttlSeconds()),
                        force ? "1" : "0"));
        return asLong(r) == 1;
    }

    /** Issues a queue token to the user, or says why not; {@code candidate} is the token to issue. */
    public Admission admit(String saleId, String userId, String candidate) {
        if (!Ids.isToken(candidate)) {
            throw new IllegalArgumentException("candidate token must be 32 lowercase hex characters");
        }
        List<?> r = asList(
                ADMIT.run(valkey, SaleKeys.of(saleId).admission(), List.of(Ids.requireUserId(userId), candidate)));
        Admission.Status status = Admission.Status.ofCode(asLong(r.get(0)));
        return new Admission(status, asString(r.get(1)));
    }

    /** Takes {@code qty} units for a user holding a valid token, atomically, or says why not. */
    public Purchase buy(String saleId, String userId, String token, int qty) {
        if (qty < 1 || qty > 100) {
            throw new IllegalArgumentException("quantity must be between 1 and 100");
        }
        if (!Ids.isToken(token)) {
            return new Purchase(Purchase.Status.BAD_TOKEN, -1);
        }
        List<?> r = asList(BUY.run(
                valkey, SaleKeys.of(saleId).all(), List.of(Ids.requireUserId(userId), token, Integer.toString(qty))));
        return new Purchase(Purchase.Status.ofCode(asLong(r.get(0))), asLong(r.get(1)));
    }

    /** Current counters of a sale. */
    public SaleStatus status(String saleId) {
        List<?> r = asList(STATUS.run(valkey, SaleKeys.of(saleId).admission(), List.of()));
        return new SaleStatus(
                asLong(r.get(0)) == 1,
                asLong(r.get(1)),
                asLong(r.get(2)),
                asLong(r.get(3)),
                asLong(r.get(4)),
                asLong(r.get(5)));
    }

    /** Atomic snapshot of the sale for the oversell check; walks every order. */
    public AuditReport audit(String saleId) {
        List<?> r = asList(AUDIT.run(valkey, SaleKeys.of(saleId).all(), List.of()));
        return new AuditReport(
                asLong(r.get(0)) == 1,
                asLong(r.get(1)),
                asLong(r.get(2)),
                asLong(r.get(3)),
                asLong(r.get(4)),
                asLong(r.get(5)),
                asLong(r.get(6)),
                asLong(r.get(7)),
                asLong(r.get(8)));
    }

    /** Round trip to Valkey, for health checks. */
    public boolean ping() {
        return "PONG".equalsIgnoreCase(valkey.ping());
    }

    /** Integer reply from a script. */
    private static long asLong(Object o) {
        if (o instanceof Long l) {
            return l;
        }
        throw new IllegalStateException("expected an integer reply, got " + o);
    }

    /** Array reply from a script. */
    private static List<?> asList(Object o) {
        if (o instanceof List<?> l) {
            return l;
        }
        throw new IllegalStateException("expected an array reply, got " + o);
    }

    /** Bulk string reply from a script, as text. */
    private static String asString(Object o) {
        if (o instanceof String s) {
            return s;
        }
        if (o instanceof byte[] b) {
            return new String(b, StandardCharsets.UTF_8);
        }
        throw new IllegalStateException("expected a string reply, got " + o);
    }
}
