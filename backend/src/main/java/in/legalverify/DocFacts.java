package in.legalverify;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** One document's Map-phase output. Built leniently because LLMs drift from the schema. */
public record DocFacts(String docType, String docNo, String date, List<String> executants, List<String> claimants,
                       String propertyId, String extent, String considerationAmt, List<String> encumbrances,
                       List<String> missingOrRedFlags, List<String> referencedPriorDocs) {

    private static final Set<String> EMPTY = Set.of("", "none", "nil", "null", "n/a", "na", "-", "not applicable");

    public static DocFacts from(JsonNode n) {
        return new DocFacts(text(n, "doc_type"), text(n, "doc_no"), text(n, "date"), list(n, "executants"),
                list(n, "claimants"), text(n, "property_id"), text(n, "extent"), text(n, "consideration_amt"),
                list(n, "encumbrances"), list(n, "missing_or_red_flags"), list(n, "referenced_prior_docs"));
    }

    private static String asString(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) return null;
        String s = (v.isValueNode() ? v.asText() : v.toString()).strip();
        return EMPTY.contains(s.toLowerCase()) ? null : s;
    }

    private static String text(JsonNode n, String field) {
        return asString(n.get(field));
    }

    private static List<String> list(JsonNode n, String field) {
        JsonNode v = n.get(field);
        List<String> out = new ArrayList<>();
        if (v != null && v.isArray()) v.forEach(e -> { String s = asString(e); if (s != null) out.add(s); });
        else { String s = asString(v); if (s != null) out.add(s); }
        return out;
    }
}
