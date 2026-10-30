package portfolio.flashsaleengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Command-line behaviour and exit codes. */
class MainTest {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        out.reset();
        err.reset();
        return Main.run(
                args,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void loadStatusAndAuditOnAFreshSale() {
        String url = Backends.mockUri().toString();
        String sale = Backends.freshSale("cli");
        assertEquals(0, run("load", "--valkey", url, "--sale", sale, "--units", "1000", "--per-user", "2"));
        assertTrue(out().contains("loaded sale " + sale + ": 1000 units, 2000 queue tokens, 2 per user"), out());
        assertEquals(1, run("load", "--valkey", url, "--sale", sale, "--units", "5"));
        assertTrue(out().contains("already loaded"), out());
        assertEquals(0, run("status", "--valkey", url, "--sale", sale));
        assertTrue(out().contains("units=1000 remaining=1000 sold=0 tokens=0/2000 per_user=2"), out());
        assertEquals(0, run("audit", "--valkey", url, "--sale", sale));
        assertTrue(out().contains("oversold=0") && out().contains("audit OK"), out());
        assertEquals(0, run("load", "--valkey", url, "--sale", sale, "--units", "5", "--force"));
    }

    @Test
    void unknownSaleFailsStatusAndAudit() {
        String url = Backends.mockUri().toString();
        assertEquals(1, run("status", "--valkey", url, "--sale", "nope-1"));
        assertEquals(1, run("audit", "--valkey", url, "--sale", "nope-1"));
        assertTrue(out().contains("violation: sale is not loaded"), out());
    }

    @Test
    void badUsageExitsTwoWithAMessage() {
        assertEquals(2, run());
        assertTrue(err().contains("usage:"));
        assertEquals(2, run("explode"));
        assertEquals(2, run("load", "--units"));
        assertTrue(err().contains("unknown option --units") || err().contains("needs a value"), err());
        assertEquals(2, run("status", "--bogus", "1"));
        assertTrue(err().contains("unknown option --bogus"), err());
        assertEquals(2, run("load", "--valkey", "http://x", "--sale", "a", "--units", "1"));
        assertTrue(err().contains("redis://"), err());
        assertEquals(2, run("load", "--valkey", Backends.mockUri().toString(), "--sale", "a", "--units", "x"));
        assertTrue(err().contains("--units must be a whole number"), err());
        assertEquals(2, run("load", "--valkey", Backends.mockUri().toString(), "--sale", "Bad!", "--units", "1"));
        assertTrue(err().contains("sale id must match"), err());
        assertEquals(2, run("load", "--valkey", Backends.mockUri().toString(), "--sale", "a"));
        assertTrue(err().contains("--units is required"), err());
    }

    @Test
    void unreachableValkeyExitsTwo() {
        assertEquals(2, run("status", "--valkey", "redis://127.0.0.1:1", "--sale", "a"));
        assertTrue(err().startsWith("error: Jedis"), err());
    }

    @Test
    void parserTakesOnlyAllowedOptions() {
        Map<String, String> m = Main.parse(new String[] {"load", "--sale", "a", "--force"}, Set.of("sale", "force"));
        assertEquals(Map.of("sale", "a", "force", "true"), m);
        assertThrows(IllegalArgumentException.class, () -> Main.parse(new String[] {"load", "sale"}, Set.of("sale")));
    }
}
