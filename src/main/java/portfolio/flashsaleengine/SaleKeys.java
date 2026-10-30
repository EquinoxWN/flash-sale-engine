package portfolio.flashsaleengine;

import java.util.List;

/**
 * Valkey keys of one sale. The {@code {saleId}} hash tag puts every key of a sale in the same
 * cluster slot, which a Lua script touching several keys requires.
 */
public record SaleKeys(String stock, String meta, String tokens, String orders) {

    /** Keys for a validated sale id. */
    public static SaleKeys of(String saleId) {
        String p = "fs:{" + Ids.requireSaleId(saleId) + "}:";
        return new SaleKeys(p + "stock", p + "meta", p + "tokens", p + "orders");
    }

    /** All four keys, in the order the load, buy and audit scripts expect. */
    public List<String> all() {
        return List.of(stock, meta, tokens, orders);
    }

    /** Stock, meta and tokens, in the order the admit and status scripts expect. */
    public List<String> admission() {
        return List.of(stock, meta, tokens);
    }
}
