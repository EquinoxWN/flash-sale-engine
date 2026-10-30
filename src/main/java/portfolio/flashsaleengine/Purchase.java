package portfolio.flashsaleengine;

/** Result of one purchase attempt; {@code remaining} is the stock left after it. */
public record Purchase(Status status, long remaining) {

    /** Outcome of buy.lua. */
    public enum Status {
        WON,
        SOLD_OUT,
        LIMIT_REACHED,
        NOT_LOADED,
        BAD_TOKEN,
        INSUFFICIENT_STOCK;

        /** Status for a buy.lua result code. */
        static Status ofCode(long code) {
            return switch ((int) code) {
                case 1 -> WON;
                case 0 -> SOLD_OUT;
                case -1 -> LIMIT_REACHED;
                case -2 -> NOT_LOADED;
                case -4 -> BAD_TOKEN;
                case -5 -> INSUFFICIENT_STOCK;
                default -> throw new IllegalStateException("unexpected buy code " + code);
            };
        }
    }
}
