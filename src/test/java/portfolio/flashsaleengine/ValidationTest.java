package portfolio.flashsaleengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ValidationTest {

    @ParameterizedTest
    @ValueSource(strings = {"a", "spring-sale", "s1", "0123456789abcdef0123456789abcdef"})
    void acceptsSaleIds(String id) {
        assertTrue(Ids.isSaleId(id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-x", "Upper", "a}b", "a:b", "a b", "0123456789abcdef0123456789abcdef0", "é"})
    void rejectsSaleIdsThatCouldEscapeTheKey(String id) {
        assertFalse(Ids.isSaleId(id));
        assertThrows(IllegalArgumentException.class, () -> SaleKeys.of(id));
    }

    @Test
    void rejectsUserIdsOutsideTheAlphabet() {
        assertTrue(Ids.isUserId("buyer_42-A"));
        assertFalse(Ids.isUserId(null));
        assertFalse(Ids.isUserId(""));
        assertFalse(Ids.isUserId("x".repeat(65)));
        assertFalse(Ids.isUserId("a\"b"));
        assertFalse(Ids.isUserId("a\r\nb"));
    }

    @Test
    void tokensAreExactly32LowercaseHex() {
        assertTrue(Ids.isToken("0123456789abcdef0123456789abcdef"));
        assertFalse(Ids.isToken("0123456789ABCDEF0123456789ABCDEF"));
        assertFalse(Ids.isToken("0123456789abcdef"));
        assertFalse(Ids.isToken(null));
    }

    @Test
    void everyKeyOfASaleSharesOneHashTag() {
        SaleKeys k = SaleKeys.of("drop-7");
        assertEquals(
                List.of("fs:{drop-7}:stock", "fs:{drop-7}:meta", "fs:{drop-7}:tokens", "fs:{drop-7}:orders"), k.all());
        assertEquals(List.of(k.stock(), k.meta(), k.tokens()), k.admission());
    }

    @Test
    void saleConfigRejectsOutOfRangeValues() {
        assertThrows(IllegalArgumentException.class, () -> new SaleConfig(-1, 1, 1, 60));
        assertThrows(IllegalArgumentException.class, () -> new SaleConfig(SaleConfig.MAX_UNITS + 1, 1, 1, 60));
        assertThrows(IllegalArgumentException.class, () -> new SaleConfig(10, 0, 1, 60));
        assertThrows(IllegalArgumentException.class, () -> new SaleConfig(10, 10, 0, 60));
        assertThrows(IllegalArgumentException.class, () -> new SaleConfig(10, 10, 101, 60));
        assertThrows(IllegalArgumentException.class, () -> new SaleConfig(10, 10, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new SaleConfig(10, 10, 1, 31L * 24 * 3600));
    }

    @Test
    void defaultConfigIssuesTwiceAsManyTokensAsUnits() {
        SaleConfig c = SaleConfig.of(1_000);
        assertEquals(2_000, c.tokenCap());
        assertEquals(1, c.perUserLimit());
        assertEquals(1, SaleConfig.of(0).tokenCap());
    }

    @Test
    void scriptsLoadWithStableHashes() {
        List<LuaScript> scripts = StockStore.scripts();
        assertEquals(5, scripts.size());
        for (LuaScript s : scripts) {
            assertTrue(s.sha1().matches("[0-9a-f]{40}"), s.name());
            assertTrue(s.source().contains("redis.call"), s.name());
        }
        assertThrows(IllegalStateException.class, () -> LuaScript.load("missing"));
    }
}
