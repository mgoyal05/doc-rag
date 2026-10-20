package docrag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FinancialToolsTest {
    private static final String FIRST = "2025-01-01/2025-03-31", SECOND = "2025-04-01/2025-06-30";

    private static FinancialTools.Snapshot snapshot(String company, String period, String basis) {
        return new FinancialTools.Snapshot(company, period, "INR", basis, "2025-07-01T10:00:00Z", "fixture report",
                Map.of("revenue", new BigDecimal(period.equals(FIRST) ? "100" : "125")), Map.of("revenue", "INR crore"));
    }

    @Test
    void computesDecimalChangesAndPreservesMissingNegativeAndZeroBaselines() {
        assertEquals(new BigDecimal("12.500000"), FinancialTools.compare(new BigDecimal("0.8"), new BigDecimal("0.9")).get("percentChange"));
        assertFalse(FinancialTools.compare(BigDecimal.ZERO, BigDecimal.ONE).containsKey("percentChange"));
        assertFalse(FinancialTools.compare(BigDecimal.ONE.negate(), BigDecimal.ONE).containsKey("percentChange"));
    }

    @Test
    void rejectsIncomparableBasisAndWrongAdapterScope() throws Exception {
        var tools = new FinancialTools((company, period) -> snapshot(company, period, period.equals(FIRST) ? "CONSOLIDATED" : "UNKNOWN"));
        var answer = tools.tools().get(1).execute(Map.of("priorCompany", "ACME", "currentCompany", "ACME", "priorPeriod", FIRST, "currentPeriod", SECOND));
        assertFalse(new ObjectMapper().readTree(answer.text()).path("available").asBoolean());
        var wrong = new FinancialTools((company, period) -> snapshot("OTHER", period, "CONSOLIDATED"));
        assertThrows(IllegalStateException.class, () -> wrong.tools().getFirst().execute(Map.of("company", "ACME", "period", FIRST)));
    }

    @Test
    void returnsSourcesUnitsAndJavaCalculations() throws Exception {
        var tool = new FinancialTools((company, period) -> snapshot(company, period, "CONSOLIDATED")).tools().get(1);
        var root = new ObjectMapper().readTree(tool.execute(Map.of("priorCompany", "ACME", "currentCompany", "ACME", "priorPeriod", FIRST, "currentPeriod", SECOND)).text());
        assertEquals("INR crore", root.path("prior").path("units").path("revenue").asText());
        assertEquals(25, root.path("changes").path("revenue").path("percentChange").asInt());
        assertEquals("2025-07-01T10:00:00Z", root.path("current").path("asOf").asText());
    }

    @Test
    void boundsSelectionFromLargeRegistryAndRejectsDuplicateNames() {
        var registry = new ToolRegistry();
        for (int i = 0; i < 1000; i++) {
            final String name = "merger_" + i;
            registry.register(new Research.Tool() {
                public String name() {
                    return name;
                }

                public String description() {
                    return "Read merger evidence";
                }

                public List<String> arguments() {
                    return List.of("company", "period");
                }

                public Research.ToolResult execute(Map<String, String> args) {
                    return new Research.ToolResult("{}", "fixture");
                }
            }, Set.of("merger"));
        }
        assertEquals(8, registry.select("Explain this merger").size());
        assertTrue(registry.select("Explain this QIP").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> registry.register(registry.select("merger").getFirst(), Set.of("merger")));
    }
}
