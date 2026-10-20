package docrag;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Page-aware native text extraction. Scans require an explicitly configured OCR adapter.
 */
public final class PdfBoxExtractor implements DocumentExtractor {
    public String version() {
        return "pdfbox-3.0.5/pages-v1";
    }

    public List<Research.Page> extract(Path file, int maxPages) throws IOException {
        try (var pdf = Loader.loadPDF(file.toFile())) {
            if (pdf.getNumberOfPages() == 0 || pdf.getNumberOfPages() > maxPages)
                throw new IOException("Document exceeds page limit or is empty");
            List<Research.Page> pages = new ArrayList<>();
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Extraction interrupted");
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(pdf).replace('\u0000', ' ').trim();
                if (text.isBlank())
                    throw new IOException("Page " + page + " has no readable text; configure Docling/OCR");
                pages.add(new Research.Page(page, text));
            }
            return List.copyOf(pages);
        }
    }
}
