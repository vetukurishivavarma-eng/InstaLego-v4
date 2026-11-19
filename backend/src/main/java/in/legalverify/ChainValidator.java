package in.legalverify;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic checks over Map-phase facts. No LLM: everything here is reproducible and explainable. */
@Component
public class ChainValidator {

    public record Doc(String fileName, String tag, DocFacts facts) {}
    public record Flag(String code, String severity, String message, List<String> docs) {}
    public record Node(String id, String label, String since) {}
    public record Edge(String from, String to, String docNo, String date, String docType, String kind, String note) {}
    public record Graph(List<Node> nodes, List<Edge> edges) {}
    public record Result(List<Doc> ordered, List<Flag> flags, Graph chainOfTitle, int riskScore, String status) {}

    public static final String CRITICAL = "CRITICAL", WARNING = "WARNING";
    private static final Map<String, Integer> WEIGHT = Map.of(CRITICAL, 30, WARNING, 10);

    private static final Pattern CONVEYANCE = Pattern.compile("(?i)sale|gift|settlement|partition|release|relinquish|\\bwill\\b|conveyance|exchange");
    private static final Pattern NOT_CONVEYANCE = Pattern.compile("(?i)encumbrance|\\bec\\b|mortgage|patta|pahani|khata|7/12|adangal|aadhaar|\\bpan\\b|tax");
    private static final Pattern DOC_NO = Pattern.compile("(?<!\\d)(\\d{1,6})\\s*/\\s*((?:19|20)\\d{2})(?!\\d)");
    private static final Map<String, Pattern> PROPERTY_PARTS = new LinkedHashMap<>();
    static {
        // "No." is optional after the full word: models write both "Survey No. 123/4A" and "Survey 123/4A"
        PROPERTY_PARTS.put("Survey No.", Pattern.compile("(?i)\\b(?:(?:sy|survey)\\.?(?:\\s*no\\.?)?|s\\.?\\s*no\\.?)\\s*[:.]?\\s*(\\d+[0-9A-Z/-]*)"));
        PROPERTY_PARTS.put("Plot No.", Pattern.compile("(?i)\\bplot(?:\\s*no\\.?)?\\s*[:.]?\\s*(\\d+[0-9A-Z/-]*)"));
        PROPERTY_PARTS.put("Door No.", Pattern.compile("(?i)\\b(?:door(?:\\s*no\\.?)?|h\\.?\\s*no\\.?)\\s*[:.]?\\s*(\\d+[0-9A-Z/-]*)"));
    }
    private static final Set<String> HONORIFICS = Set.of("SRI", "SHRI", "SMT", "SMTI", "KUM", "KUMARI", "MR", "MRS", "MS", "DR", "LATE", "MESSRS");
    private static final Pattern RELATION = Pattern.compile("(?i)\\s*,?\\s*\\b(?:[SDWC]\\s*/\\s*O|SON OF|WIFE OF|DAUGHTER OF|AGED)\\b.*$");
    private static final String[][] PHONETIC = {{"KSH", "X"}, {"SH", "S"}, {"TH", "T"}, {"DH", "D"}, {"BH", "B"}, {"PH", "F"},
            {"KH", "K"}, {"GH", "G"}, {"CH", "C"}, {"W", "V"}, {"Z", "J"}, {"EE", "I"}, {"OO", "U"}, {"AA", "A"}};

    enum Match { EXACT, VARIANT, NONE }

    public Result validate(List<Doc> docs) {
        List<Doc> ordered = new ArrayList<>(docs);
        ordered.sort(Comparator.comparing((Doc d) -> parseDate(d.facts().date()), Comparator.nullsLast(Comparator.naturalOrder())));
        List<Flag> flags = new ArrayList<>();

        for (Doc d : ordered) {
            if (parseDate(d.facts().date()) == null)
                flags.add(new Flag("UNDATED", WARNING, "Date could not be read for " + label(d) + "; it is placed last in the chain.", List.of(label(d))));
            for (String e : d.facts().encumbrances())
                flags.add(new Flag("ENCUMBRANCE", WARNING, "Encumbrance recorded in " + label(d) + ": " + e, List.of(label(d))));
            Set<String> encumbered = new HashSet<>();
            d.facts().encumbrances().forEach(e -> encumbered.addAll(docNumbers(e)));
            for (String r : d.facts().missingOrRedFlags())
                if (docNumbers(r).stream().noneMatch(encumbered::contains)) // models repeat "no release of 801/2021" here; count it once
                    flags.add(new Flag("DOCUMENT_RED_FLAG", WARNING, label(d) + ": " + r, List.of(label(d))));
        }

        Graph graph = checkContinuity(ordered.stream().filter(ChainValidator::isConveyance).toList(), flags);
        checkPropertyIds(ordered, flags);
        checkMissingReferences(ordered, flags);

        int score = Math.min(100, flags.stream().mapToInt(f -> WEIGHT.getOrDefault(f.severity(), 0)).sum());
        return new Result(ordered, flags, graph, score, status(score, flags));
    }

    static String status(int score, List<Flag> flags) {
        boolean critical = flags.stream().anyMatch(f -> CRITICAL.equals(f.severity()));
        if (score >= 60) return "DEFECTIVE";
        return critical || score >= 20 ? "CONDITIONAL" : "CLEAR";
    }

    static boolean isConveyance(Doc d) {
        if (d.tag() != null) switch (d.tag()) {
            case "SALE_DEED", "LINK_DEED" -> { return true; }
            case "EC", "PATTA", "ID" -> { return false; }
            default -> { }
        }
        String type = Objects.toString(d.facts().docType(), "");
        return CONVEYANCE.matcher(type).find() && !NOT_CONVEYANCE.matcher(type).find();
    }

    /** Claimants of deed N must be the executants of deed N+1. Builds the owner graph as it goes. */
    private Graph checkContinuity(List<Doc> chain, List<Flag> flags) {
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        Node holders = null;
        Doc prev = null;
        for (Doc d : chain) {
            DocFacts f = d.facts();
            Node from = holders;
            String note = null;
            if (prev != null) {
                Match worst = Match.EXACT;
                List<String> strangers = new ArrayList<>();
                for (String ex : f.executants()) {
                    Match best = prev.facts().claimants().stream().map(c -> matchNames(c, ex)).min(Comparator.naturalOrder()).orElse(Match.NONE);
                    if (best == Match.NONE) strangers.add(ex);
                    else if (best == Match.VARIANT) worst = Match.VARIANT;
                }
                List<String> refs = List.of(label(prev), label(d));
                if (f.executants().isEmpty() || strangers.size() == f.executants().size()) {
                    flags.add(new Flag("CHAIN_BREAK", CRITICAL, "Break in title: executant(s) of " + label(d) + " " + f.executants()
                            + " are not the claimant(s) of " + label(prev) + " " + prev.facts().claimants() + ".", refs));
                    from = null;
                } else {
                    if (!strangers.isEmpty())
                        flags.add(new Flag("EXTRA_EXECUTANT", WARNING, strangers + " executed " + label(d)
                                + " but did not acquire title in " + label(prev) + "; confirm their capacity.", refs));
                    if (worst == Match.VARIANT) {
                        note = "name spelt differently";
                        flags.add(new Flag("NAME_VARIANCE", WARNING, "Name spelt differently between " + label(prev) + " " + prev.facts().claimants()
                                + " and " + label(d) + " " + f.executants() + "; an identity affidavit is advisable.", refs));
                    }
                }
            }
            if (from == null) {
                from = new Node("n" + nodes.size(), String.join(", ", f.executants().isEmpty() ? List.of("Unknown") : f.executants()), null);
                nodes.add(from);
                if (holders != null)
                    edges.add(new Edge(holders.id(), from.id(), null, null, null, "break", "no deed connects these owners"));
            }
            holders = new Node("n" + nodes.size(), String.join(", ", f.claimants().isEmpty() ? List.of("Unknown") : f.claimants()), f.date());
            nodes.add(holders);
            edges.add(new Edge(from.id(), holders.id(), f.docNo(), f.date(), f.docType(), "transfer", note));
            prev = d;
        }
        return new Graph(nodes, edges);
    }

    private void checkPropertyIds(List<Doc> docs, List<Flag> flags) {
        for (var part : PROPERTY_PARTS.entrySet()) {
            Map<String, List<String>> seen = new LinkedHashMap<>(); // normalised value -> docs
            for (Doc d : docs) {
                Matcher m = part.getValue().matcher(Objects.toString(d.facts().propertyId(), ""));
                if (m.find()) seen.computeIfAbsent(m.group(1).toUpperCase().replaceAll("[-/]+$", ""), k -> new ArrayList<>()).add(label(d));
            }
            if (seen.size() > 1) {
                StringBuilder sb = new StringBuilder(part.getKey() + " differs across documents: ");
                seen.forEach((v, ds) -> sb.append(v).append(" in ").append(ds).append("; "));
                flags.add(new Flag("PROPERTY_ID_MISMATCH", CRITICAL, sb.toString().replaceAll("; $", ".") + " A rectification deed may be required.",
                        seen.values().stream().flatMap(List::stream).distinct().toList()));
            }
        }
        for (Doc d : docs)
            if (d.facts().propertyId() == null && isConveyance(d))
                flags.add(new Flag("PROPERTY_ID_MISSING", WARNING, "No survey/plot/door number found in " + label(d) + ".", List.of(label(d))));
    }

    private void checkMissingReferences(List<Doc> docs, List<Flag> flags) {
        Set<String> uploaded = new HashSet<>();
        docs.forEach(d -> uploaded.addAll(docNumbers(d.facts().docNo())));
        Map<String, Set<String>> missing = new TreeMap<>();
        for (Doc d : docs)
            for (String ref : d.facts().referencedPriorDocs())
                for (String no : docNumbers(ref))
                    if (!uploaded.contains(no)) missing.computeIfAbsent(no, k -> new LinkedHashSet<>()).add(label(d));
        missing.forEach((no, by) -> flags.add(new Flag("MISSING_DOCUMENT", WARNING,
                "Doc No. " + no + " is referred to in " + by + " but was not uploaded.", List.copyOf(by))));
    }

    static List<String> docNumbers(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = DOC_NO.matcher(Objects.toString(s, ""));
        while (m.find()) out.add(Integer.parseInt(m.group(1)) + "/" + m.group(2));
        return out;
    }

    static LocalDate parseDate(String s) {
        if (s == null) return null;
        for (String fmt : List.of("yyyy-MM-dd", "dd-MM-yyyy", "dd/MM/yyyy", "dd.MM.yyyy", "d-M-yyyy", "d/M/yyyy")) {
            try { return LocalDate.parse(s.strip(), DateTimeFormatter.ofPattern(fmt)); } catch (Exception ignored) { }
        }
        return null;
    }

    static String label(Doc d) {
        String no = d.facts().docNo();
        return no != null ? "Doc " + no : d.fileName();
    }

    // ---- names ----

    static List<String> nameTokens(String name) {
        String n = RELATION.matcher(Objects.toString(name, "")).replaceAll("").toUpperCase().replaceAll("\\bM/S\\b", " ");
        List<String> out = new ArrayList<>();
        for (String t : n.split("[^A-Z]+"))
            if (!t.isEmpty() && !HONORIFICS.contains(t)) out.add(t);
        return out;
    }

    static String phonetic(String t) {
        for (String[] r : PHONETIC) t = t.replace(r[0], r[1]);
        return t.replaceAll("(.)\\1+", "$1");
    }

    static Match matchTokens(String a, String b) {
        if (a.equals(b)) return Match.EXACT;
        if ((a.length() == 1 && b.startsWith(a)) || (b.length() == 1 && a.startsWith(b))) return Match.EXACT; // initial
        if (a.length() == 1 || b.length() == 1) return Match.NONE;
        String pa = phonetic(a), pb = phonetic(b);
        return pa.equals(pb) || (Math.min(pa.length(), pb.length()) >= 5 && levenshtein(pa, pb) <= 1) ? Match.VARIANT : Match.NONE;
    }

    /** Every token of the shorter name must match a distinct token of the longer, and at least one must be a full word. */
    static Match matchNames(String x, String y) {
        List<String> a = nameTokens(x), b = nameTokens(y);
        if (a.isEmpty() || b.isEmpty()) return Match.NONE;
        if (a.size() > b.size()) { var t = a; a = b; b = t; }
        List<String> pool = new ArrayList<>(b);
        Match worst = Match.EXACT;
        boolean fullWord = false;
        for (String t : a) {
            String chosen = null;
            Match chosenMatch = Match.NONE;
            for (String c : pool) {
                Match m = matchTokens(t, c);
                if (m.compareTo(chosenMatch) < 0) { chosen = c; chosenMatch = m; }
                if (m == Match.EXACT) break;
            }
            if (chosen == null) return Match.NONE;
            pool.remove(chosen);
            if (chosenMatch == Match.VARIANT) worst = Match.VARIANT;
            if (t.length() > 1 && chosen.length() > 1) fullWord = true;
        }
        return fullWord ? worst : Match.NONE;
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++)
                cur[j] = Math.min(Math.min(cur[j - 1], prev[j]) + 1, prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }
}
