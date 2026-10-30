package portfolio.flashsaleengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The oversell checker itself: each invariant fails on its own. */
class AuditReportTest {

    @Test
    void consistentSaleHasNoViolations() {
        AuditReport a = new AuditReport(true, 0, 10, 1, 12, 10, 10, 1, 0);
        assertTrue(a.ok());
        assertEquals(10, a.sold());
        assertEquals(0, a.oversold());
    }

    @Test
    void negativeStockIsOverselling() {
        AuditReport a = new AuditReport(true, -3, 10, 1, 20, 13, 13, 1, 0);
        assertFalse(a.ok());
        assertEquals(3, a.oversold());
        assertTrue(a.violations().contains("stock counter is negative: -3"));
        assertTrue(a.violations().contains("orders hold 13 units but only 10 were loaded"));
    }

    @Test
    void counterAndOrdersMustAgree() {
        AuditReport a = new AuditReport(true, 5, 10, 1, 10, 4, 4, 1, 0);
        assertEquals(List.of("stock counter says 5 sold but orders hold 4"), a.violations());
        assertEquals(0, a.oversold());
    }

    @Test
    void perUserLimitAndTokenlessWinnersAreFlagged() {
        AuditReport a = new AuditReport(true, 7, 10, 1, 1, 3, 2, 2, 1);
        assertEquals(
                List.of("a user holds 2 units, above the limit of 1", "1 winners hold no queue token"), a.violations());
    }

    @Test
    void missingSaleIsNotOk() {
        AuditReport a = new AuditReport(false, 0, 0, 0, 0, 0, 0, 0, 0);
        assertEquals(List.of("sale is not loaded"), a.violations());
    }
}
