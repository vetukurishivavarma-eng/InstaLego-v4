package in.legalverify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Upload -> OCR -> Map (Groq 429s, Cerebras answers) -> rules -> Reduce -> paywall, against fake HTTP services.
 * The fake OCR echoes the file name as text and the fake Map model answers with mocks/map-outputs/<name>.json.
 */
class PipelineTest {
    private static final Path MOCKS = Path.of("..", "mocks", "map-outputs");
    private static final List<String> FILES = List.of("2012-sale-deed", "2019-gift-deed-survey-typo", "2024-ec");

    private final ObjectMapper json = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final Map<String, String> redisData = new ConcurrentHashMap<>();
    private final Set<String> cooling = ConcurrentHashMap.newKeySet();
    private final AtomicInteger ocrFiles = new AtomicInteger(), groq = new AtomicInteger(), cerebras = new AtomicInteger(), reduce = new AtomicInteger();
    private final List<CaseDocument> docs = Collections.synchronizedList(new ArrayList<>());
    private HttpServer server;
    private CaseController controller;
    private VerificationOrchestratorService orchestrator;
    private CaseRecord saved;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ocr/api/ocr/extract", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            List<Map<String, Object>> out = new ArrayList<>();
            for (String f : FILES)
                if (body.contains("filename=\"" + f + ".pdf\"")) {
                    ocrFiles.incrementAndGet();
                    out.add(Map.of("file_name", f + ".pdf", "sha256_hash", VerificationOrchestratorService.sha256(bytes(f)),
                            "cleaned_text", f, "page_count", 1));
                }
            send(ex, 200, json.writeValueAsString(out));
        });
        server.createContext("/groq/chat/completions", ex -> { ex.getRequestBody().readAllBytes(); groq.incrementAndGet(); send(ex, 429, "{}"); });
        server.createContext("/cerebras/chat/completions", ex -> {
            JsonNode req = new ObjectMapper().readTree(ex.getRequestBody().readAllBytes());
            cerebras.incrementAndGet();
            send(ex, 200, chat(Files.readString(MOCKS.resolve(req.at("/messages/1/content").asText() + ".json"))));
        });
        server.createContext("/gemini/chat/completions", ex -> {
            ex.getRequestBody().readAllBytes();
            reduce.incrementAndGet();
            send(ex, 200, chat("{\"risk_score\":5,\"status\":\"CLEAR\",\"chronological_summary\":[{\"date\":\"2012-03-14\",\"doc_no\":\"2345/2012\",\"event\":\"Sale to P. Lakshmi\"}],"
                    + "\"critical_red_flags\":[\"Mortgage 801/2021 to SBI is not discharged\"],\"missing_documents_checklist\":[\"Release deed for 801/2021\"],"
                    + "\"opinion\":[{\"heading\":\"Conclusion\",\"body\":\"Title is not marketable until rectified.\"}]}"));
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(i -> redisData.get(i.<String>getArgument(0)));
        doAnswer(i -> redisData.put(i.getArgument(0), i.getArgument(1))).when(ops).set(anyString(), anyString());
        doAnswer(i -> cooling.add(i.getArgument(0))).when(ops).set(anyString(), anyString(), any(Duration.class));
        when(redis.hasKey(anyString())).thenAnswer(i -> cooling.contains(i.<String>getArgument(0)));

        CaseRepository cases = mock(CaseRepository.class);
        when(cases.save(any())).thenAnswer(i -> saved = i.getArgument(0));
        when(cases.findById(any())).thenAnswer(i -> Optional.ofNullable(saved));
        CaseDocumentRepository documents = mock(CaseDocumentRepository.class);
        when(documents.save(any())).thenAnswer(i -> { docs.add(i.getArgument(0)); return i.getArgument(0); });
        when(documents.findByCaseIdOrderById(any())).thenAnswer(i -> List.copyOf(docs));

        var props = new LlmProperties(Duration.ofSeconds(5), Map.of(
                "map", List.of(new LlmProperties.Provider("groq", base + "/groq", "k", "m"), new LlmProperties.Provider("cerebras", base + "/cerebras", "k", "m")),
                "reduce", List.of(new LlmProperties.Provider("gemini", base + "/gemini", "k", "m"))));
        var router = new LlmRouterService(props, redis, json, builder());
        orchestrator = new VerificationOrchestratorService(router, new ChainValidator(), redis, json, cases, documents, builder(), base + "/ocr");
        controller = new CaseController(cases, orchestrator, new PaymentService("mock", "", "", 499, builder()), json);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private RestClient.Builder builder() {
        return RestClient.builder().messageConverters(c -> {
            c.removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
            c.add(new MappingJackson2HttpMessageConverter(json));
        });
    }

    private static byte[] bytes(String f) {
        return ("%PDF fake " + f).getBytes(StandardCharsets.UTF_8);
    }

    private VerificationOrchestratorService.Upload upload(String f, String tag) {
        return new VerificationOrchestratorService.Upload(f + ".pdf", tag, bytes(f));
    }

    private static String chat(String content) throws java.io.IOException {
        return new ObjectMapper().writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content", content)))));
    }

    private static void send(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @Test
    void fullFlowWithFailoverCacheAndPaywall() {
        UUID id = UUID.fromString(controller.create().get("case_id").asText());

        // first two documents; Groq is rate-limited so Cerebras serves the Map phase
        orchestrator.addDocuments(saved, List.of(upload("2012-sale-deed", "SALE_DEED"), upload("2019-gift-deed-survey-typo", "OTHER")));
        int groqHits = groq.get(); // both parallel Map calls may reach Groq before the first 429 lands
        assertTrue(groqHits >= 1 && groqHits <= 2);
        assertEquals(2, cerebras.get());
        assertEquals(2, redisData.keySet().stream().filter(k -> k.startsWith("ocr:map:")).count());

        // add the missing EC later, re-sending the sale deed: only the EC is OCR'd and mapped
        orchestrator.addDocuments(saved, List.of(upload("2012-sale-deed", "SALE_DEED"), upload("2024-ec", "EC")));
        assertEquals(3, ocrFiles.get());
        assertEquals(3, cerebras.get());
        assertEquals(groqHits, groq.get(), "Groq is cooling down, so it is not called again");
        assertEquals(3, docs.size());
        assertEquals(2, reduce.get(), "one Reduce call per analysis");

        JsonNode preview = controller.get(id);
        assertEquals(70, preview.get("risk_score").asInt(), "LLM said 5; it may not lower the rule-engine score");
        assertEquals("DEFECTIVE", preview.get("status").asText());
        assertEquals(3, preview.at("/chain_of_title/nodes").size());
        assertEquals(2, preview.get("red_flag_count").asInt(), "survey mismatch (rules) + undischarged mortgage (LLM)");
        assertEquals(3, preview.get("missing_document_count").asInt());
        assertFalse(preview.has("opinion") || preview.has("critical_red_flags"), "paid sections must not reach an unpaid client");
        assertEquals(402, assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> controller.report(id)).getStatusCode().value());

        JsonNode full = controller.confirm(id, null);
        assertTrue(full.get("paid").asBoolean());
        assertEquals("Conclusion", full.at("/opinion/0/heading").asText());
        assertTrue(full.get("critical_red_flags").toString().contains("123/A4"));
        byte[] pdf = assertDoesNotThrow(() -> controller.report(id)).getBody();
        assertTrue(pdf.length > 1000 && new String(pdf, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-"));
    }

    @Test
    void reduceOutageStillGivesTheFreePreview() throws Exception {
        server.removeContext("/gemini/chat/completions");
        server.createContext("/gemini/chat/completions", ex -> { ex.getRequestBody().readAllBytes(); send(ex, 503, "{}"); });
        UUID id = UUID.fromString(controller.create().get("case_id").asText());
        orchestrator.addDocuments(saved, List.of(upload("2012-sale-deed", "SALE_DEED"), upload("2019-gift-deed-survey-typo", "OTHER")));
        JsonNode preview = controller.get(id);
        assertEquals("CONDITIONAL", preview.get("status").asText());
        assertTrue(preview.has("reduce_error"));
        assertEquals(2, preview.at("/chain_of_title/edges").size());
    }
}
