package docrag;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Portable tools/calculations; applications supply stored facts through a read-only adapter.
 */
public final class FinancialTools {
    private final DataSource data;
    private final ObjectMapper json = new ObjectMapper();

    public FinancialTools(DataSource data) {
        this.data = Objects.requireNonNull(data);
    }

    private static boolean known(String value) {
        return value != null && !value.isBlank() && !value.equalsIgnoreCase("UNKNOWN");
    }

    public static Map<String, BigDecimal> compare(BigDecimal prior, BigDecimal current) {
        Objects.requireNonNull(prior);
        Objects.requireNonNull(current);
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        BigDecimal delta = current.subtract(prior);
        result.put("absoluteChange", delta);
        if (prior.signum() > 0)
            result.put("percentChange", delta.multiply(BigDecimal.valueOf(100)).divide(prior, 6, RoundingMode.HALF_UP));
        return Collections.unmodifiableMap(result);
    }

    public List<Research.Tool> tools() {
        return List.of(new Research.Tool() {
            public String name() {
                return "get_financial_metrics";
            }

            public String description() {
                return "Read stored financial metrics for one company and ISO start/end period, with metric units, currency, basis, source and as-of time.";
            }

            public List<String> arguments() {
                return List.of("company", "period");
            }

            public Research.ToolResult execute(Map<String, String> args) throws Exception {
                var snapshot = load(args.get("company"), args.get("period"));
                return new Research.ToolResult(snapshot == null ? "{\"available\":false}" : json.writeValueAsString(snapshot), snapshot == null ? "stored financial data" : snapshot.source(), facts(snapshot));
            }
        }, new Research.Tool() {
            public String name() {
                return "compare_financial_metrics";
            }

            public String description() {
                return "Compute financial differences in Java. Periods are ISO start/end. Peer comparisons require the same reporting dates; all calculations require verified matching currency, units and basis.";
            }

            public List<String> arguments() {
                return List.of("priorCompany", "currentCompany", "priorPeriod", "currentPeriod");
            }

            public Research.ToolResult execute(Map<String, String> args) throws Exception {
                var prior = load(args.get("priorCompany"), args.get("priorPeriod"));
                var current = load(args.get("currentCompany"), args.get("currentPeriod"));
                if (!comparable(prior, current))
                    return new Research.ToolResult("{\"available\":false,\"reason\":\"Missing or incomparable dates, currency, units or reporting basis\"}", "stored financial data");
                Map<String, Object> changes = new LinkedHashMap<>();
                List<Research.Fact> facts = new ArrayList<>();
                facts.addAll(facts(prior));
                facts.addAll(facts(current));
                for (String metric : prior.values().keySet())
                    if (current.values().containsKey(metric)
                            && known(prior.units().get(metric)) && prior.units().get(metric).equals(current.units().get(metric))) {
                        var comparison = compare(prior.values().get(metric), current.values().get(metric));
                        changes.put(metric, comparison);
                        comparison.forEach((kind, value) -> facts.add(new Research.Fact("F" + (facts.size() + 1), "", metric + " " + kind, value,
                                kind.equals("percentChange") ? "N/A" : current.currency(), kind.equals("percentChange") ? "%" : current.units().get(metric),
                                current.basis(), current.asOf(), prior.source() + " ; " + current.source(),
                                List.of(new Research.Target(prior.company(), prior.period()), new Research.Target(current.company(), current.period())), "", true)));
                    }
                for (int i = 0; i < facts.size(); i++) facts.set(i, facts.get(i).referenced("F" + (i + 1), ""));
                return new Research.ToolResult(json.writeValueAsString(Map.of("prior", prior, "current", current, "changes", changes,
                        "percentWarning", "Percent change omitted for zero or negative baselines")), prior.source() + " ; " + current.source(), facts);
            }
        });
    }

    private List<Research.Fact> facts(Snapshot snapshot) {
        if (snapshot == null || !known(snapshot.currency()) || !known(snapshot.basis()) || !known(snapshot.asOf()) || !known(snapshot.source()))
            return List.of();
        List<Research.Fact> facts = new ArrayList<>();
        snapshot.values().forEach((metric, value) -> {
            if (known(snapshot.units().get(metric)))
                facts.add(new Research.Fact("F" + (facts.size() + 1), "", metric, value, snapshot.currency(), snapshot.units().get(metric),
                        snapshot.basis(), snapshot.asOf(), snapshot.source(), List.of(new Research.Target(snapshot.company(), snapshot.period())), "", false));
        });
        return List.copyOf(facts);
    }

    private Snapshot load(String company, String period) throws Exception {
        var snapshot = data.load(company, period);
        if (snapshot != null && (!company.equals(snapshot.company()) || !period.equals(snapshot.period())))
            throw new IllegalStateException("Financial adapter returned a different scope");
        return snapshot;
    }

    private boolean comparable(Snapshot a, Snapshot b) {
        if (a == null || b == null || !known(a.currency()) || !a.currency().equals(b.currency()) || !known(a.basis()) || !a.basis().equals(b.basis())
                || !known(a.asOf()) || !known(b.asOf()) || !known(a.source()) || !known(b.source()))
            return false;
        if (!a.company().equals(b.company())) return a.period().equals(b.period());
        try {
            String[] x = a.period().split("/"), y = b.period().split("/");
            long first = java.time.temporal.ChronoUnit.MONTHS.between(LocalDate.parse(x[0]), LocalDate.parse(x[1]).plusDays(1));
            long second = java.time.temporal.ChronoUnit.MONTHS.between(LocalDate.parse(y[0]), LocalDate.parse(y[1]).plusDays(1));
            return first == second && first > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @FunctionalInterface
    public interface DataSource {
        Snapshot load(String company, String period) throws Exception;
    }

    public record Snapshot(String company, String period, String currency, String basis, String asOf, String source,
                           Map<String, BigDecimal> values, Map<String, String> units) {
        public Snapshot {
            values = Map.copyOf(values);
            units = Map.copyOf(units);
        }
    }
}
