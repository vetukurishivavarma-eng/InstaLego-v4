package in.legalverify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Fake providers on a local HTTP server; Redis is an in-memory map. No real API quota is used. */
class LlmRouterServiceTest {
    private HttpServer server;
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    private final Map<String, Duration> cooldowns = new HashMap<>();      // what was set, for assertions
    private final Map<String, java.time.Instant> expires = new HashMap<>(); // so cooldowns really expire
    private final Map<String, String> values = new HashMap<>();
    private StringRedisTemplate redis;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        route("/ratelimited", 429, "{\"error\":{\"details\":[{\"retryDelay\":\"90s\"}]}}");
        route("/down", 503, "{}");
        route("/wrapped", 200, reply("[{\"doc_no\":\"801/2021\"}]"));
        route("/noquota", 429, "{\"error\":{\"message\":\"Quota exceeded, limit: 0\",\"details\":[{\"retryDelay\":\"2s\"}]}}");
        route("/badkey", 401, "{}");
        route("/prose", 200, reply("Sure! Here is the data you asked for."));
        AtomicInteger flaky = new AtomicInteger();
        server.createContext("/flaky", ex -> {
            ex.getRequestBody().readAllBytes();
            hits.computeIfAbsent("/flaky", k -> new AtomicInteger()).incrementAndGet();
            boolean first = flaky.getAndIncrement() == 0;
            byte[] b = (first ? "{}" : reply("{\"ok\":true}")).getBytes(StandardCharsets.UTF_8);
            if (first) ex.getResponseHeaders().add("Retry-After", "1");
            ex.sendResponseHeaders(first ? 429 : 200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        route("/ok", 200, reply("```json\n{\"doc_no\":\"2345/2012\"}\n```"));
        server.start();

        redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(redis.hasKey(anyString())).thenAnswer(i -> remaining(i.getArgument(0)) > 0);
        when(redis.getExpire(anyString())).thenAnswer(i -> remaining(i.getArgument(0)));
        when(ops.get(anyString())).thenAnswer(i -> remaining(i.getArgument(0)) > 0 ? values.get(i.<String>getArgument(0)) : null);
        doAnswer(i -> {
            String k = i.getArgument(0);
            Duration d = i.getArgument(2);
            cooldowns.put(k, d);
            values.put(k, i.getArgument(1));
            return expires.put(k, java.time.Instant.now().plus(d));
        }).when(ops).set(anyString(), anyString(), any(Duration.class));
    }

    private long remaining(String k) {
        var e = expires.get(k);
        return e == null ? -2 : Math.max(0, Duration.between(java.time.Instant.now(), e).toSeconds());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static String reply(String content) {
        try {
            return new ObjectMapper().writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content", content)))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void route(String path, int status, String body) {
        server.createContext(path, ex -> {
            hits.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getRequestBody().readAllBytes();
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
    }

    private LlmProperties.Provider p(String name, String key) {
        return new LlmProperties.Provider(name, "http://127.0.0.1:" + server.getAddress().getPort() + "/" + name, key, "m");
    }

    private LlmRouterService router(List<LlmProperties.Provider> pool) {
        return new LlmRouterService(new LlmProperties(Duration.ofSeconds(5), Map.of("map", pool)), redis, new ObjectMapper(), RestClient.builder());
    }

    @Test
    void failsOverAndCoolsDownExhaustedProviders() {
        var router = router(List.of(p("nokey", ""), p("ratelimited", "k"), p("down", "k"), p("badkey", "k"), p("prose", "k"), p("ok", "k")));

        assertEquals("2345/2012", router.completeJson("map", "s", "u", 0).get("doc_no").asText());
        assertEquals(Duration.ofSeconds(90), cooldowns.get("llm:cooldown:ratelimited:m"), "Gemini-style retryDelay honoured");
        assertEquals(LlmRouterService.SERVER_ERROR, cooldowns.get("llm:cooldown:down:m"));
        assertEquals(LlmRouterService.BAD_KEY, cooldowns.get("llm:cooldown:badkey:m"));
        assertFalse(cooldowns.containsKey("llm:cooldown:prose:m"), "a non-JSON reply is not a quota problem");

        router.completeJson("map", "s", "u", 0);
        assertEquals(1, hits.get("/ratelimited").get(), "cooling-down provider is skipped without a request");
        assertEquals(1, hits.get("/down").get());
        assertEquals(2, hits.get("/prose").get());
        assertEquals(2, hits.get("/ok").get());
    }

    @Test
    void throwsWhenEveryProviderIsUnavailable() {
        var router = router(List.of(p("ratelimited", "k"), p("down", "k")));
        var e = assertThrows(LlmRouterService.AllProvidersFailedException.class, () -> router.completeJson("map", "s", "u", 0));
        assertTrue(e.getMessage().contains("HTTP 429") && e.getMessage().contains("HTTP 503"), e.getMessage());
        assertThrows(LlmRouterService.AllProvidersFailedException.class, () -> router.completeJson("map", "s", "u", 0));
        assertEquals(1, hits.get("/ratelimited").get());
    }

    @Test
    void waitsOutAShortRateLimitWhenNoProviderIsLeft() {
        var router = router(List.of(p("flaky", "k")));
        long t = System.nanoTime();
        assertTrue(router.completeJson("map", "s", "u", 0).get("ok").asBoolean());
        assertTrue(Duration.ofNanos(System.nanoTime() - t).toMillis() >= 1000, "waited for Retry-After");
        assertEquals(2, hits.get("/flaky").get(), "the 429, then one retry");
    }

    @Test
    void zeroQuotaModelIsBenchedNotWaitedFor() {
        var router = router(List.of(p("noquota", "k")));
        long t = System.nanoTime();
        assertThrows(LlmRouterService.AllProvidersFailedException.class, () -> router.completeJson("map", "s", "u", 0));
        assertTrue(Duration.ofNanos(System.nanoTime() - t).toMillis() < 1500, "no 2s wait for a quota that is 0");
        assertEquals(LlmRouterService.BAD_KEY, cooldowns.get("llm:cooldown:noquota:m"));
    }

    @Test
    void unwrapsASingleObjectArray() {
        assertEquals("801/2021", router(List.of(p("wrapped", "k"))).completeJson("map", "s", "u", 0).get("doc_no").asText());
    }
}
