package portfolio.flashsaleengine;

import java.util.regex.Pattern;

/** Validation of the identifiers that reach Valkey keys, Lua arguments and JSON responses. */
public final class Ids {
    private static final Pattern SALE = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
    private static final Pattern USER = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{32}");

    private Ids() {}

    /** True for a sale id: lowercase letters, digits and dashes, 1 to 32 characters. */
    public static boolean isSaleId(String s) {
        return s != null && SALE.matcher(s).matches();
    }

    /** True for a user id: letters, digits, underscore and dash, 1 to 64 characters. */
    public static boolean isUserId(String s) {
        return s != null && USER.matcher(s).matches();
    }

    /** True for a queue token: 32 lowercase hex characters. */
    public static boolean isToken(String s) {
        return s != null && TOKEN.matcher(s).matches();
    }

    /** The sale id, or an exception naming the rule. */
    public static String requireSaleId(String s) {
        if (!isSaleId(s)) {
            throw new IllegalArgumentException("sale id must match [a-z0-9][a-z0-9-]{0,31}");
        }
        return s;
    }

    /** The user id, or an exception naming the rule. */
    public static String requireUserId(String s) {
        if (!isUserId(s)) {
            throw new IllegalArgumentException("user id must match [A-Za-z0-9_-]{1,64}");
        }
        return s;
    }
}
