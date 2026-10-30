package portfolio.flashsaleengine;

import java.util.ArrayList;
import java.util.List;

/**
 * Oversell check over one atomic snapshot of a sale.
 *
 * @param loaded whether the sale exists
 * @param remaining stock counter
 * @param units units loaded
 * @param perUserLimit most units one user may hold
 * @param tokensIssued queue tokens handed out
 * @param unitsInOrders sum of every winner's quantity
 * @param winners users holding at least one unit
 * @param largestOrder largest quantity held by one user
 * @param winnersWithoutToken winners that never received a queue token
 */
public record AuditReport(
        boolean loaded,
        long remaining,
        long units,
        long perUserLimit,
        long tokensIssued,
        long unitsInOrders,
        long winners,
        long largestOrder,
        long winnersWithoutToken) {

    /** Units sold according to the stock counter. */
    public long sold() {
        return units - remaining;
    }

    /** Units handed out beyond the loaded stock; zero when the sale is correct. */
    public long oversold() {
        return Math.max(Math.max(0, unitsInOrders - units), Math.max(0, -remaining));
    }

    /** Every broken invariant; empty when the sale is consistent. */
    public List<String> violations() {
        List<String> out = new ArrayList<>();
        if (!loaded) {
            out.add("sale is not loaded");
            return out;
        }
        if (remaining < 0) {
            out.add("stock counter is negative: " + remaining);
        }
        if (unitsInOrders > units) {
            out.add("orders hold " + unitsInOrders + " units but only " + units + " were loaded");
        }
        if (sold() != unitsInOrders) {
            out.add("stock counter says " + sold() + " sold but orders hold " + unitsInOrders);
        }
        if (largestOrder > perUserLimit) {
            out.add("a user holds " + largestOrder + " units, above the limit of " + perUserLimit);
        }
        if (winnersWithoutToken > 0) {
            out.add(winnersWithoutToken + " winners hold no queue token");
        }
        return out;
    }

    /** True when no invariant is broken. */
    public boolean ok() {
        return violations().isEmpty();
    }
}
