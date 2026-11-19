package in.legalverify;

import com.fasterxml.jackson.databind.JsonNode;
import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;

import java.awt.Color;
import java.io.ByteArrayOutputStream;

final class ReportPdf {
    private static final Font H1 = new Font(Font.HELVETICA, 16, Font.BOLD);
    private static final Font H2 = new Font(Font.HELVETICA, 12, Font.BOLD);
    private static final Font BODY = new Font(Font.HELVETICA, 10);
    private static final Font SMALL = new Font(Font.HELVETICA, 8, Font.ITALIC, Color.DARK_GRAY);

    private ReportPdf() {}

    static byte[] render(CaseRecord c, JsonNode a) {
        var out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 50, 50, 50, 50);
        PdfWriter.getInstance(doc, out);
        doc.open();
        doc.add(new Paragraph("Title Verification Report", H1));
        doc.add(new Paragraph("Case " + c.id + "  |  " + c.createdAt.toString().substring(0, 10), SMALL));
        doc.add(new Paragraph("Status: " + a.path("status").asText() + "    Risk score: " + a.path("risk_score").asInt() + " / 100", H2));

        section(doc, "Documents scrutinised");
        a.path("documents").forEach(d -> line(doc, "- " + d.path("doc_type").asText("Document") + "  No. " + d.path("doc_no").asText("-")
                + "  dated " + d.path("date").asText("-") + "  (" + d.path("file_name").asText() + ")"));
        section(doc, "Chronology");
        a.path("chronological_summary").forEach(s -> line(doc,
                s.path("date").asText("") + "  Doc " + s.path("doc_no").asText("") + ": " + s.path("event").asText("")));
        section(doc, "Critical red flags");
        a.path("critical_red_flags").forEach(f -> line(doc, "- " + f.asText()));
        section(doc, "Missing documents checklist");
        a.path("missing_documents_checklist").forEach(f -> line(doc, "[ ] " + f.asText()));
        a.path("opinion").forEach(s -> {
            section(doc, s.path("heading").asText(""));
            line(doc, s.path("body").asText(""));
        });
        doc.add(new Paragraph("\nAI-assisted draft prepared from the uploaded copies. It must be checked against the originals and "
                + "approved by an enrolled advocate before it is relied on.", SMALL));
        doc.close();
        return out.toByteArray();
    }

    private static void section(Document doc, String title) {
        Paragraph p = new Paragraph(title, H2);
        p.setSpacingBefore(12);
        doc.add(p);
    }

    private static void line(Document doc, String text) {
        doc.add(new Paragraph(text, BODY));
    }
}
