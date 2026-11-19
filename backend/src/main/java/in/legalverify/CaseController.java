package in.legalverify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/cases")
public class CaseController {
    private static final List<String> FREE = List.of("risk_score", "status", "chain_of_title", "documents", "reduce_error");
    private static final List<String> PAID = List.of("critical_red_flags", "missing_documents_checklist", "chronological_summary", "opinion", "rule_flags");

    private final CaseRepository cases;
    private final VerificationOrchestratorService orchestrator;
    private final PaymentService payments;
    private final ObjectMapper json;

    public CaseController(CaseRepository cases, VerificationOrchestratorService orchestrator, PaymentService payments, ObjectMapper json) {
        this.cases = cases;
        this.orchestrator = orchestrator;
        this.payments = payments;
        this.json = json;
    }

    @PostMapping
    public JsonNode create() {
        return view(cases.save(new CaseRecord()));
    }

    @GetMapping("/{id}")
    public JsonNode get(@PathVariable UUID id) {
        return view(find(id));
    }

    @PostMapping(path = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public JsonNode upload(@PathVariable UUID id, @RequestParam("files") List<MultipartFile> files,
                           @RequestParam(value = "tags", required = false) List<String> tags) throws IOException {
        if (files.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No files");
        List<VerificationOrchestratorService.Upload> uploads = new ArrayList<>();
        for (int i = 0; i < files.size(); i++)
            uploads.add(new VerificationOrchestratorService.Upload(files.get(i).getOriginalFilename(),
                    tags != null && i < tags.size() ? tags.get(i) : "OTHER", files.get(i).getBytes()));
        return view(orchestrator.addDocuments(find(id), uploads));
    }

    @PostMapping("/{id}/reanalyze")
    public JsonNode reanalyze(@PathVariable UUID id) {
        return view(orchestrator.analyze(find(id)));
    }

    @PostMapping("/{id}/checkout")
    public Map<String, Object> checkout(@PathVariable UUID id) {
        CaseRecord c = find(id);
        if (!c.paid && !payments.isMock()) {
            c.orderId = payments.createOrder(c.id.toString());
            cases.save(c);
        }
        return Map.of("mode", payments.isMock() ? "mock" : "razorpay", "key_id", payments.keyId,
                "order_id", c.orderId == null ? "" : c.orderId, "amount_inr", payments.priceInr);
    }

    public record PaymentConfirmation(String razorpayPaymentId, String razorpaySignature) {}

    @PostMapping("/{id}/payment")
    public JsonNode confirm(@PathVariable UUID id, @RequestBody(required = false) PaymentConfirmation p) {
        CaseRecord c = find(id);
        if (!c.paid) {
            boolean ok = payments.verify(c.orderId, p == null ? null : p.razorpayPaymentId(), p == null ? null : p.razorpaySignature());
            if (!ok) throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "Payment could not be verified");
            c.paid = true;
            cases.save(c);
        }
        return view(c);
    }

    @GetMapping(value = "/{id}/report.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> report(@PathVariable UUID id) throws IOException {
        CaseRecord c = find(id);
        if (!c.paid) throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "Unlock the report first");
        if (c.analysisJson == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "No documents analysed yet");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"title-report-" + id + ".pdf\"")
                .body(ReportPdf.render(c, json.readTree(c.analysisJson)));
    }

    /** Paid sections never leave the server before payment; any blur in the UI is cosmetic only. */
    private JsonNode view(CaseRecord c) {
        ObjectNode out = json.createObjectNode().put("case_id", c.id.toString()).put("paid", c.paid)
                .put("price_inr", payments.priceInr).put("payment_mode", payments.isMock() ? "mock" : "razorpay");
        if (c.analysisJson == null) return out;
        JsonNode a;
        try {
            a = json.readTree(c.analysisJson);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        FREE.forEach(k -> { if (a.has(k)) out.set(k, a.get(k)); });
        out.put("red_flag_count", a.path("critical_red_flags").size());
        out.put("missing_document_count", a.path("missing_documents_checklist").size());
        if (c.paid) PAID.forEach(k -> { if (a.has(k)) out.set(k, a.get(k)); });
        return out;
    }

    private CaseRecord find(UUID id) {
        return cases.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found"));
    }

    @ExceptionHandler(LlmRouterService.AllProvidersFailedException.class)
    ResponseEntity<Map<String, String>> llmDown(LlmRouterService.AllProvidersFailedException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error",
                "All AI providers are busy or out of free quota. Files already read are saved; retry the upload in a few minutes."));
    }

    /** Only the OCR service is called through a throwing RestClient here; LLM errors are absorbed by the router. */
    @ExceptionHandler(RestClientResponseException.class)
    ResponseEntity<Map<String, String>> upstream(RestClientResponseException e) {
        String detail = e.getResponseBodyAsString();
        try {
            detail = json.readTree(detail).path("detail").asText(detail);
        } catch (IOException ignored) { }
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", detail));
    }
}
