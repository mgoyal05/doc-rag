package docrag;

import java.util.*;

/**
 * Explicitly registered, trusted read-only capabilities. No classpath/plugin auto-execution.
 */
public final class ToolRegistry {
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public static ToolRegistry financial(FinancialTools.DataSource source) {
        var registry = new ToolRegistry();
        for (var tool : new FinancialTools(source).tools())
            registry.register(tool, Set.of("revenue", "sales", "profit", "income", "eps", "margin", "financial", "growth", "percentage", "percent", "compare", "comparison"));
        return registry;
    }

    public synchronized ToolRegistry register(Research.Tool tool, Set<String> keywords) {
        if (entries.size() >= 1000 || tool == null || !tool.name().matches("[A-Za-z][A-Za-z0-9_]{0,63}") || entries.containsKey(tool.name()))
            throw new IllegalArgumentException("Invalid/duplicate tool or registry limit exceeded");
        if (keywords.isEmpty()) throw new IllegalArgumentException("Tool selection keywords are required");
        entries.put(tool.name(), new Entry(tool, keywords.stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet())));
        return this;
    }

    public synchronized List<Research.Tool> select(String question) {
        String text = " " + question.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", " ") + " ";
        return entries.values().stream().map(e -> Map.entry(e, e.keywords().stream().filter(k -> text.contains(" " + k + " ")).count()))
                .filter(e -> e.getValue() > 0).sorted(Map.Entry.<Entry, Long>comparingByValue().reversed().thenComparing(e -> e.getKey().tool().name()))
                .limit(8).map(e -> e.getKey().tool()).toList();
    }

    private record Entry(Research.Tool tool, Set<String> keywords) {
    }
}
