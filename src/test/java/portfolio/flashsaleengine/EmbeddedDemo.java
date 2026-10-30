package portfolio.flashsaleengine;

import com.github.fppt.jedismock.RedisServer;
import java.io.IOException;

/**
 * {@code make demo}: runs a CLI command against an in-process jedis-mock server, for machines
 * without Valkey. Usage: the same arguments as {@link Main}, without {@code --valkey}.
 */
public final class EmbeddedDemo {
    private EmbeddedDemo() {}

    /** Starts the server, runs the command, stops the server; fails when the command fails. */
    public static void main(String[] args) throws IOException {
        RedisServer server = RedisServer.newRedisServer(0).start();
        int code;
        try {
            String[] full = new String[args.length + 2];
            System.arraycopy(args, 0, full, 0, args.length);
            full[args.length] = "--valkey";
            full[args.length + 1] = "redis://" + server.getHost() + ":" + server.getBindPort();
            System.out.println(
                    "backend: jedis-mock (in-process Redis-protocol server running Lua on LuaJ), not Valkey");
            code = Main.run(full, System.out, System.err);
        } finally {
            server.stop();
        }
        if (code != 0) {
            throw new IllegalStateException("command exited with code " + code);
        }
    }
}
