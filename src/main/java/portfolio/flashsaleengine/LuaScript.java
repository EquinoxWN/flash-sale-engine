package portfolio.flashsaleengine;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.exceptions.JedisNoScriptException;

/** A Lua script from the classpath, run by its SHA-1 and sent in full only when Valkey lacks it. */
public final class LuaScript {
    private final String name;
    private final String source;
    private final String sha1;

    private LuaScript(String name, String source) {
        this.name = name;
        this.source = source;
        this.sha1 = sha1Hex(source);
    }

    /** Loads {@code /lua/<name>.lua} from the classpath. */
    public static LuaScript load(String name) {
        String path = "/lua/" + name + ".lua";
        try (InputStream in = LuaScript.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing script " + path);
            }
            return new LuaScript(name, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Script name without extension. */
    public String name() {
        return name;
    }

    /** Lua source text. */
    public String source() {
        return source;
    }

    /** SHA-1 that Valkey uses to cache the script. */
    public String sha1() {
        return sha1;
    }

    /** Runs the script with EVALSHA, falling back to EVAL (which caches it) on NOSCRIPT. */
    public Object run(UnifiedJedis valkey, List<String> keys, List<String> args) {
        try {
            return valkey.evalsha(sha1, keys, args);
        } catch (JedisNoScriptException e) {
            return valkey.eval(source, keys, args);
        }
    }

    /** Hex SHA-1 of the UTF-8 source. */
    private static String sha1Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
