package portfolio.flashsaleengine;

/**
 * Settings fixed when a sale's stock is loaded.
 *
 * @param units units for sale
 * @param tokenCap most queue tokens issued; buyers past it are shed before they reach stock
 * @param perUserLimit most units one user may buy
 * @param ttlSeconds lifetime of the sale's keys in Valkey
 */
public record SaleConfig(long units, long tokenCap, int perUserLimit, long ttlSeconds) {
    /** Upper bound on units, so the audit script's walk over orders stays short. */
    public static final long MAX_UNITS = 10_000_000L;

    /** Upper bound on queue tokens. */
    public static final long MAX_TOKENS = 100_000_000L;

    /** Validates every field. */
    public SaleConfig {
        if (units < 0 || units > MAX_UNITS) {
            throw new IllegalArgumentException("units must be between 0 and " + MAX_UNITS);
        }
        if (tokenCap < 1 || tokenCap > MAX_TOKENS) {
            throw new IllegalArgumentException("token cap must be between 1 and " + MAX_TOKENS);
        }
        if (perUserLimit < 1 || perUserLimit > 100) {
            throw new IllegalArgumentException("per-user limit must be between 1 and 100");
        }
        if (ttlSeconds < 1 || ttlSeconds > 30L * 24 * 3600) {
            throw new IllegalArgumentException("ttl must be between 1 second and 30 days");
        }
    }

    /** A sale of {@code units} with twice as many queue tokens, one unit per user and a one-day TTL. */
    public static SaleConfig of(long units) {
        return new SaleConfig(units, Math.max(1, units * 2), 1, 24 * 3600);
    }
}
