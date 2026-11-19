package in.legalverify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** OCR -> Map (per document, cached by file hash) -> Java rules -> one Reduce call -> stored analysis. */
@Service
public class VerificationOrchestratorService {
    private static final Logger log = LoggerFactory.getLogger(VerificationOrchestratorService.class);
    public static final Set<String> TAGS = Set.of("SALE_DEED", "LINK_DEED", "EC", "PATTA", "ID", "OTHER");
    private static final List<String> STATUS_ORDER = List.of("CLEAR", "CONDITIONAL", "DEFECTIVE");

    static final String MAP_PROMPT = """
            Extract legal facts from the OCR text into STRICT minified JSON. No markdown, no explanations. Use null if missing. \
            Normalize Indian revenue terms (Patta, Pahani, 7/12, Khata, EC) into English equivalents with original term in brackets. \
            executants = parties giving/creating rights; claimants = parties receiving them; names only, without S/o, W/o or age. \
            property_id: every identifier of the schedule property exactly as printed (Survey No., Plot No., Door/House No., \
            Patta/Khata No., village); never drop the survey number. \
            doc_no: "NUMBER/YEAR" for a registered deed, otherwise the number exactly as printed. consideration_amt: price actually \
            paid; null for a gift or settlement (stamp-duty market value is not consideration). For an Encumbrance Certificate, executants and claimants are [], put \
            every listed transaction's doc no in referenced_prior_docs and every mortgage/lien/attachment with no recorded release \
            in encumbrances, each written as "nature, doc no, in favour of, amount". missing_or_red_flags: other defects only, never repeat an item already in encumbrances.
            Schema: {"doc_type":"","doc_no":"","date":"YYYY-MM-DD","executants":[],"claimants":[],"property_id":"","extent":"",\
            "consideration_amt":"","encumbrances":[],"missing_or_red_flags":[],"referenced_prior_docs":[]}""";

    static final String REDUCE_PROMPT = """
            You are a senior Indian property-law advocate writing a title verification opinion. Input: JSON with \
            "documents" (facts extracted from each uploaded document) and "rule_engine_flags" (deterministic checks that are \
            reliable; never contradict them). Use only facts in the input; cite Document Numbers and Dates exactly as given; \
            never invent documents, names, numbers or law sections you are unsure of. Reply with STRICT JSON only:
            {"risk_score":0-100,"status":"CLEAR|CONDITIONAL|DEFECTIVE",\
            "chronological_summary":[{"date":"YYYY-MM-DD","doc_no":"","event":""}],\
            "critical_red_flags":[""],"missing_documents_checklist":[""],\
            "opinion":[{"heading":"","body":""}]}
            All strings are plain text: no markdown, no ** or #. critical_red_flags and missing_documents_checklist: only items \
            NOT already stated in rule_engine_flags (those are shown to the reader automatically); [] if nothing new. \
            "opinion" is the full opinion, section by section: Documents Scrutinised, Flow of Title, Encumbrances, \
            Discrepancies, Requisitions (what the buyer must obtain before purchase), Conclusion.""";

    public record OcrResult(String fileName, String sha256Hash, String cleanedText, int pageCount) {}

    private final LlmRouterService llm;
    private final ChainValidator validator;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final CaseRepository cases;
    private final CaseDocumentRepository documents;
    private final RestClient ocr;

    public VerificationOrchestratorService(LlmRouterService llm, ChainValidator validator, StringRedisTemplate redis, ObjectMapper json,
                                           CaseRepository cases, CaseDocumentRepository documents, RestClient.Builder builder,
                                           @Value("${ocr.url}") String ocrUrl, @Value("${ocr.token:}") String ocrToken) {
        this.llm = llm;
        this.validator = validator;
        this.redis = redis;
        this.json = json;
        this.cases = cases;
        this.documents = documents;
        this.ocr = builder.baseUrl(ocrUrl).defaultHeader("X-OCR-Token", ocrToken).build();
    }

    public record Upload(String fileName, String tag, byte[] bytes) {}

    /** Adds documents to a case (already-seen files cost nothing) and re-runs the analysis over the whole bundle. */
    public CaseRecord addDocuments(CaseRecord c, List<Upload> uploads) {
        Set<String> inCase = new HashSet<>();
        documents.findByCaseIdOrderById(c.id).forEach(d -> inCase.add(d.sha256));
        Map<String, Upload> fresh = new LinkedHashMap<>();
        for (Upload u : uploads) {
            String sha = sha256(u.bytes());
            if (!inCase.contains(sha)) fresh.putIfAbsent(sha, u);
        }
        Map<String, String> facts = mapPhase(fresh);
        fresh.forEach((sha, u) -> {
            CaseDocument d = new CaseDocument();
            d.caseId = c.id;
            d.fileName = u.fileName();
            d.tag = TAGS.contains(u.tag()) ? u.tag() : "OTHER";
            d.sha256 = sha;
            d.factsJson = facts.get(sha);
            documents.save(d);
        });
        return analyze(c);
    }

    /** sha -> facts JSON. Redis first; only cache misses are OCR'd and sent to the Map pool, in parallel. */
    private Map<String, String> mapPhase(Map<String, Upload> fresh) {
        Map<String, String> out = new HashMap<>();
        Map<String, Upload> misses = new LinkedHashMap<>();
        fresh.forEach((sha, u) -> {
            String hit = redis.opsForValue().get(cacheKey(sha));
            if (hit != null) out.put(sha, hit); else misses.put(sha, u);
        });
        if (misses.isEmpty()) return out;

        List<OcrResult> texts = runOcr(misses.values());
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, Future<String>> jobs = new LinkedHashMap<>();
            for (OcrResult t : texts)
                jobs.put(t.sha256Hash(), pool.submit(() -> json.writeValueAsString(
                        DocFacts.from(llm.completeJson("map", MAP_PROMPT, t.cleanedText(), 0.0)))));
            for (var e : jobs.entrySet()) {
                String factsJson = e.getValue().get();
                redis.opsForValue().set(cacheKey(e.getKey()), factsJson); // content-addressed: never stale, no TTL
                out.put(e.getKey(), factsJson);
            }
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re; // finished documents stay cached for the retry
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return out;
    }

    private List<OcrResult> runOcr(Collection<Upload> uploads) {
        var form = new LinkedMultiValueMap<String, Object>();
        for (Upload u : uploads)
            form.add("files", new ByteArrayResource(u.bytes()) {
                @Override public String getFilename() { return u.fileName(); }
            });
        return ocr.post().uri("/api/ocr/extract").contentType(MediaType.MULTIPART_FORM_DATA).body(form)
                .retrieve().body(new ParameterizedTypeReference<List<OcrResult>>() {});
    }

    /** Rules + Reduce over every document in the case. A Reduce outage still leaves the rule-based preview. */
    public CaseRecord analyze(CaseRecord c) {
        List<ChainValidator.Doc> docs = new ArrayList<>();
        for (CaseDocument d : documents.findByCaseIdOrderById(c.id)) {
            try {
                docs.add(new ChainValidator.Doc(d.fileName, d.tag, DocFacts.from(json.readTree(d.factsJson))));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Stored facts unreadable for " + d.fileName, e);
            }
        }
        ChainValidator.Result rules = validator.validate(docs);

        ObjectNode a = json.createObjectNode();
        a.set("chain_of_title", json.valueToTree(rules.chainOfTitle()));
        a.set("rule_flags", json.valueToTree(rules.flags()));
        ArrayNode docList = a.putArray("documents");
        for (var d : rules.ordered())
            docList.addObject().put("file_name", d.fileName()).put("tag", d.tag()).put("doc_type", d.facts().docType())
                    .put("doc_no", d.facts().docNo()).put("date", d.facts().date());

        JsonNode reduce = null;
        try {
            ObjectNode input = json.createObjectNode();
            ArrayNode in = input.putArray("documents");
            rules.ordered().forEach(d -> ((ObjectNode) in.addObject().put("file_name", d.fileName()).put("tag", d.tag()))
                    .set("facts", json.valueToTree(d.facts())));
            input.set("rule_engine_flags", json.valueToTree(rules.flags()));
            reduce = llm.completeJson("reduce", REDUCE_PROMPT, json.writeValueAsString(input), 0.1);
        } catch (LlmRouterService.AllProvidersFailedException | JsonProcessingException e) {
            log.warn("Reduce failed for case {}: {}", c.id, e.getMessage());
            a.put("reduce_error", "The written opinion could not be generated right now (all AI providers busy). Re-run analysis later.");
        }
        merge(a, rules, reduce);
        c.analysisJson = a.toString();
        return cases.save(c);
    }

    /** The LLM may raise the risk the rules found, never lower it. */
    void merge(ObjectNode a, ChainValidator.Result rules, JsonNode reduce) {
        JsonNode r = reduce == null ? json.createObjectNode() : reduce;
        int llmScore = r.path("risk_score").isNumber() ? Math.max(0, Math.min(100, r.path("risk_score").asInt())) : 0;
        a.put("risk_score", Math.max(rules.riskScore(), llmScore));
        int llmStatus = STATUS_ORDER.indexOf(r.path("status").asText("").toUpperCase());
        a.put("status", STATUS_ORDER.get(Math.max(STATUS_ORDER.indexOf(rules.status()), llmStatus)));

        Set<String> red = new LinkedHashSet<>(), missing = new LinkedHashSet<>();
        for (var f : rules.flags()) {
            if (ChainValidator.CRITICAL.equals(f.severity())) red.add(f.message());
            if ("MISSING_DOCUMENT".equals(f.code())) missing.add(f.message());
        }
        r.path("critical_red_flags").forEach(n -> { if (n.isTextual() && !n.asText().isBlank()) red.add(n.asText()); });
        r.path("missing_documents_checklist").forEach(n -> { if (n.isTextual() && !n.asText().isBlank()) missing.add(n.asText()); });
        a.set("critical_red_flags", json.valueToTree(red));
        a.set("missing_documents_checklist", json.valueToTree(missing));
        a.set("chronological_summary", objectsOnly(r.path("chronological_summary"), "event"));
        a.set("opinion", objectsOnly(r.path("opinion"), "body"));
    }

    /** Models sometimes pad arrays with "" or stray strings; keep only objects that carry the required text field. */
    private ArrayNode objectsOnly(JsonNode arr, String required) {
        ArrayNode out = json.createArrayNode();
        arr.forEach(n -> { if (n.isObject() && !n.path(required).asText("").isBlank()) out.add(n); });
        return out;
    }

    static String cacheKey(String sha) {
        return "ocr:map:" + sha;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
