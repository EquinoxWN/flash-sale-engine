package portfolio.flashsaleengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.exceptions.JedisNoScriptException;

/** Every result code of the five Lua scripts, against each backend. */
class LuaScriptsTest {
    private static final String T1 = "11111111111111111111111111111111";
    private static final String T2 = "22222222222222222222222222222222";
    private static final String T3 = "33333333333333333333333333333333";

    static List<Backends.Backend> backends() {
        return Backends.all();
    }

    @ParameterizedTest
    @MethodSource("backends")
    void loadRefusesToResetAnOpenSaleUnlessForced(Backends.Backend b) {
        try (RedisClient c = b.client(4)) {
            StockStore s = new StockStore(c);
            String sale = Backends.freshSale("load");
            assertTrue(s.load(sale, new SaleConfig(10, 20, 2, 600), false));
            assertEquals(new SaleStatus(true, 10, 10, 0, 20, 2), s.status(sale));
            assertTrue(s.admit(sale, "u1", T1).admitted());
            assertTrue(s.buy(sale, "u1", T1, 1).status() == Purchase.Status.WON);

            assertFalse(s.load(sale, new SaleConfig(99, 99, 1, 600), false));
            assertEquals(9, s.status(sale).remaining());

            assertTrue(s.load(sale, new SaleConfig(5, 5, 1, 600), true));
            assertEquals(new SaleStatus(true, 5, 5, 0, 5, 1), s.status(sale));
            assertEquals(0, s.audit(sale).winners());
        }
    }

    @ParameterizedTest
    @MethodSource("backends")
    void everySaleKeyExpires(Backends.Backend b) {
        try (RedisClient c = b.client(4)) {
            StockStore s = new StockStore(c);
            String sale = Backends.freshSale("ttl");
            s.load(sale, new SaleConfig(3, 3, 1, 600), false);
            s.admit(sale, "u1", T1);
            s.buy(sale, "u1", T1, 1);
            for (String key : SaleKeys.of(sale).all()) {
                long ttl = c.ttl(key);
                assertTrue(ttl > 0 && ttl <= 600, key + " ttl " + ttl);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("backends")
    void admitIssuesOneTokenPerUserUpToTheCap(Backends.Backend b) {
        try (RedisClient c = b.client(4)) {
            StockStore s = new StockStore(c);
            String sale = Backends.freshSale("admit");
            assertEquals(Admission.Status.NOT_LOADED, s.admit(sale, "u1", T1).status());
            s.load(sale, new SaleConfig(5, 2, 1, 600), false);

            assertEquals(new Admission(Admission.Status.ISSUED, T1), s.admit(sale, "u1", T1));
            assertEquals(new Admission(Admission.Status.ALREADY_ISSUED, T1), s.admit(sale, "u1", T2));
            assertEquals(new Admission(Admission.Status.ISSUED, T2), s.admit(sale, "u2", T2));
            assertEquals(Admission.Status.QUEUE_FULL, s.admit(sale, "u3", T3).status());
            assertEquals(2, s.status(sale).tokensIssued());
        }
    }

    @ParameterizedTest
    @MethodSource("backends")
    void admitRefusesNewTokensOnceSoldOut(Backends.Backend b) {
        try (RedisClient c = b.client(4)) {
            StockStore s = new StockStore(c);
            String sale = Backends.freshSale("admit-out");
            s.load(sale, new SaleConfig(1, 10, 1, 600), false);
            s.admit(sale, "u1", T1);
            assertEquals(Purchase.Status.WON, s.buy(sale, "u1", T1, 1).status());
            assertEquals(Admission.Status.SOLD_OUT, s.admit(sale, "u2", T2).status());
            assertEquals(
                    Admission.Status.ALREADY_ISSUED, s.admit(sale, "u1", T3).status());
        }
    }

    @ParameterizedTest
    @MethodSource("backends")
    void buyNeedsTheUsersOwnToken(Backends.Backend b) {
        try (RedisClient c = b.client(4)) {
            StockStore s = new StockStore(c);
            String sale = Backends.freshSale("token");
            s.load(sale, new SaleConfig(5, 5, 1, 600), false);
            s.admit(sale, "u1", T1);
            s.admit(sale, "u2", T2);
            assertEquals(Purchase.Status.BAD_TOKEN, s.buy(sale, "u3", T1, 1).status());
            assertEquals(Purchase.Status.BAD_TOKEN, s.buy(sale, "u1", T2, 1).status());
            assertEquals(
                    Purchase.Status.BAD_TOKEN,
                    s.buy(sale, "u1", "not-a-token", 1).status());
            assertEquals(new Purchase(Purchase.Status.WON, 4), s.buy(sale, "u1", T1, 1));
            assertEquals(4, s.status(sale).remaining());
        }
    }

    @ParameterizedTest
    @MethodSource("backends")
    void buyEnforcesThePerUserLimit(Backends.Backend b) {
        try (RedisClient c = b.client(4)) {
            StockStore s = new StockStore(c);
            String sale = Backends.freshSale("limit");
            s.load(sale, new SaleConfig(10, 10, 3, 600), false);
            s.admit(sale, "u1", T1);
            assertEquals(new Purchase(Purchase.Status.WON, 8), s.buy(sale, "u1", T1, 2));
            assertEquals(new Purchase(Purchase.Status.LIMIT_REACHED, 8), s.buy(sale, "u1", T1, 2));
            assertEquals(new Purchase(Purchase.Status.WON, 7), s.buy(sale, "u1", T1, 1));
            assertEquals(new Purchase(Purchase.Status.LIMIT_REACHED, 7), s.buy(sale, "u1", T1, 1));
            AuditReport a = s.audit(sale);
            assertEquals(3, a.largestOrder());
            assertTrue(a.ok(), a.violations().toString());
        }
    }

    @ParameterizedTest
    @MethodSource("backends")
    void buyNeverTakesMoreThanIsLeft(Backends.Backend b) {
        try (RedisClient c = b.client(4)) {
            StockStore s = new StockStore(c);
            String sale = Backends.freshSale("left");
            s.load(sale, new SaleConfig(3, 10, 5, 600), false);
            s.admit(sale, "u1", T1);
            s.admit(sale, "u2", T2);
            assertEquals(new Purchase(Purchase.Status.WON, 1), s.buy(sale, "u1", T1, 2));
            assertEquals(new Purchase(Purchase.Status.INSUFFICIENT_STOCK, 1), s.buy(sale, "u2", T2, 2));
            assertEquals(new Purchase(Purchase.Status.WON, 0), s.buy(sale, "u2", T2, 1));
            assertEquals(new Purchase(Purchase.Status.SOLD_OUT, 0), s.buy(sale, "u2", T2, 1));
            AuditReport a = s.audit(sale);
            assertEquals(3, a.unitsInOrders());
            assertEquals(0, a.oversold());
            assertTrue(a.ok(), a.violations().toString());
        }
    }

    @ParameterizedTest
    @MethodSource("backends")
    void unknownSaleIsReportedNotLoaded(Backends.Backend b) {
        try (RedisClient c = b.client(4)) {
            StockStore s = new StockStore(c);
            String sale = Backends.freshSale("none");
            assertEquals(Purchase.Status.NOT_LOADED, s.buy(sale, "u1", T1, 1).status());
            assertFalse(s.status(sale).loaded());
            assertFalse(s.audit(sale).loaded());
            assertFalse(s.audit(sale).ok());
        }
    }

    @ParameterizedTest
    @MethodSource("backends")
    void auditFlagsAStockCounterThatWasTamperedWith(Backends.Backend b) {
        try (RedisClient c = b.client(4)) {
            StockStore s = new StockStore(c);
            String sale = Backends.freshSale("tamper");
            s.load(sale, new SaleConfig(2, 4, 1, 600), false);
            s.admit(sale, "u1", T1);
            s.buy(sale, "u1", T1, 1);
            c.hset(SaleKeys.of(sale).orders(), "intruder", "1");
            c.hset(SaleKeys.of(sale).orders(), "u1", "2");
            AuditReport a = s.audit(sale);
            assertFalse(a.ok());
            assertEquals(3, a.unitsInOrders());
            assertEquals(1, a.oversold());
            assertEquals(1, a.winnersWithoutToken());
        }
    }

    @ParameterizedTest
    @MethodSource("backends")
    void scriptsAreResentWhenTheServerForgetsThem(Backends.Backend b) {
        try (RedisClient c = b.client(4)) {
            StockStore s = new StockStore(c);
            String sale = Backends.freshSale("flush");
            s.load(sale, new SaleConfig(2, 2, 1, 600), false);
            c.scriptFlush();
            LuaScript status = StockStore.scripts().get(3);
            List<String> keys = SaleKeys.of(sale).admission();
            assertThrows(JedisNoScriptException.class, () -> c.evalsha(status.sha1(), keys, List.of()));
            assertEquals(2, s.status(sale).remaining());
            assertTrue(s.admit(sale, "u1", T1).admitted());
            assertEquals(List.of(1L, 2L, 2L, 1L, 2L, 1L), c.evalsha(status.sha1(), keys, List.of()));
        }
    }
}
