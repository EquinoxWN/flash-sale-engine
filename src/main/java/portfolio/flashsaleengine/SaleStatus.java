package portfolio.flashsaleengine;

/** Counters of one sale, read in one atomic step. */
public record SaleStatus(
        boolean loaded, long remaining, long units, long tokensIssued, long tokenCap, long perUserLimit) {

    /** Units sold so far. */
    public long sold() {
        return units - remaining;
    }
}
