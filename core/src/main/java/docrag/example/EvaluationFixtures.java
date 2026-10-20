package docrag.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Synthetic, redistributable ground truth: five companies, ten PDFs, fifty versioned questions.
 */
public final class EvaluationFixtures {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: outputDirectory");
        generate(Path.of(args[0]));
    }

    public static void generate(Path directory) throws Exception {
        Files.createDirectories(directory.resolve("reports"));
        var json = new ObjectMapper();
        List<String> manifest = new ArrayList<>();
        List<String> questions = new ArrayList<>();
        int companyIndex = 0;
        for (String company : List.of("ACME", "BRAVO", "CEDAR", "DELTA", "EMBER")) {
            int multiplier = ++companyIndex;
            for (int quarter = 1; quarter <= 2; quarter++) {
                String id = company + "-Q" + quarter;
                String filename = "reports/" + id + ".pdf";
                List<String> lines = List.of(company + " FY2026-Q" + quarter + " - SYNTHETIC FIXTURE",
                        quarter == 1 ? "Reporting dates: 2025-04-01 to 2025-06-30." : "Reporting dates: 2025-07-01 to 2025-09-30.",
                        "Reporting basis: consolidated. Currency: INR. Units: crore.",
                        "Revenue: " + (quarter == 1 ? 100 : 120) * multiplier + " crore.",
                        "Net profit: " + (quarter == 1 ? 10 : 12) * multiplier + " crore.",
                        quarter == 1 ? "Management commentary: pilot launched in west region." : "Management commentary: expanded distribution in east region.",
                        quarter == 1 ? "The pilot phase began during the quarter." : "Revenue and net profit each increased 20 percent from FY2026-Q1.");
                try (var pdf = new PDDocument()) {
                    var page = new PDPage();
                    pdf.addPage(page);
                    try (var content = new PDPageContentStream(pdf, page)) {
                        content.beginText();
                        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                        content.setLeading(20);
                        content.newLineAtOffset(40, 740);
                        for (String line : lines) {
                            content.showText(line);
                            content.newLine();
                        }
                        content.endText();
                    }
                    pdf.save(directory.resolve(filename).toFile());
                }
                manifest.add(json.writeValueAsString(Map.of("id", id, "company", company, "period", quarter == 1 ? "2025-04-01/2025-06-30" : "2025-07-01/2025-09-30",
                        "file", filename, "source", "synthetic:" + id)));
            }
            for (int test = 1; test <= 10; test++) {
                List<String> periods = test <= 6 ? List.of("FY2026-Q" + (test % 2 == 1 ? 1 : 2))
                        : test == 10 ? List.of("FY2026-Q3") : List.of("FY2026-Q1", "FY2026-Q2");
                String question = switch (test) {
                    case 1, 2 -> "What revenue and units are explicitly stated in this reporting period?";
                    case 3, 4 -> "What net profit and units are explicitly stated in this reporting period?";
                    case 5 -> "What did management say about its operating developments?";
                    case 6 ->
                            "Compare the revenue stated by " + company + " and " + (company.equals("ACME") ? "BRAVO" : "ACME") + " in FY2026-Q2.";
                    case 7 -> "Compare management commentary between the two periods.";
                    case 8 -> "What percentage change does the Q2 report explicitly state for revenue and net profit?";
                    case 9 -> "What dividend per share was declared? Return no claims if the evidence does not say.";
                    default -> "What revenue was reported in Q3?";
                };
                List<String> expected = test == 10 ? List.of() : test == 6 ? List.of(company + "-Q2", (company.equals("ACME") ? "BRAVO" : "ACME") + "-Q2")
                        : periods.stream().map(period -> company + "-" + period.substring(7)).toList();
                if (test == 2) question = "And what about its revenue in FY2026-Q2?";
                else if (test != 6) question = company + ", " + String.join(" and ", periods) + ": " + question;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", company + "-" + test);
                row.put("company", company);
                row.put("periods", periods);
                row.put("question", question);
                row.put("expectedDocuments", expected);
                row.put("expectedAbstention", test >= 9);
                row.put("expectedRevenueQ1", 100 * multiplier);
                row.put("expectedRevenueQ2", 120 * multiplier);
                row.put("expectedPercentChange", 20);
                row.put("split", test <= 6 ? "development" : "heldout");
                row.put("review", "synthetic fixture specification; real-corpus review required");
                questions.add(json.writeValueAsString(row));
            }
        }
        Files.write(directory.resolve("manifest.jsonl"), manifest);
        Files.write(directory.resolve("questions.jsonl"), questions);
    }
}
