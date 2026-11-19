package in.legalverify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tries each provider in a pool in order. 429/5xx/timeout/bad key put that provider+model on a Redis cooldown
 * (shared by all backend replicas) and the next provider is tried at once. Every provider speaks the OpenAI
 * chat-completions format, so there is one HTTP client and no per-provider code.
 */
@Service
public class LlmRouterService {
    private static final Logger log = LoggerFactory.getLogger(LlmRouterService.class);
    static final Duration RATE_LIMIT_DEFAULT = Duration.ofSeconds(60);
    static final Duration SERVER_ERROR = Duration.ofSeconds(30);
    static final Duration TIMED_OUT = Duration.ofMinutes(5); // an overloaded model stays slow for a while; don't make every case wait on it
    static final Duration BAD_KEY = Duration.ofHours(1);
    static final Duration MAX_COOLDOWN = Duration.ofHours(24);
    static final Duration MAX_WAIT = Duration.ofSeconds(60); // ponytail: blocks the request thread (virtual, so cheap); a job queue if waits grow
    private static final Pattern RETRY_DELAY = Pattern.compile("\"retryDelay\"\\s*:\\s*\"(\\d+)(?:\\.\\d+)?s\"");
    private static final Pattern FENCE = Pattern.compile("^```(?:json)?\\s*|\\s*```$");

    private final LlmProperties props;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final RestClient http;

    public LlmRouterService(LlmProperties props, StringRedisTemplate redis, ObjectMapper json, RestClient.Builder builder) {
        this.props = props;
        this.redis = redis;
        this.json = json;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(props.timeout());
        this.http = builder.requestFactory(factory).build();
    }

    public static class AllProvidersFailedException extends RuntimeException {
        AllProvidersFailedException(String pool, List<String> reasons) {
            super("All '" + pool + "' providers failed or are cooling down: " + reasons);
        }
    }

    /** Returns the model's reply parsed as a JSON object. */
    public JsonNode completeJson(String pool, String system, String user, double temperature) {
        Duration[] shortestRateLimit = {null};
        try {
            return attempt(pool, system, user, temperature, shortestRateLimit);
        } catch (AllProvidersFailedException e) {
            // Free tiers reset per minute: if everything is only briefly rate-limited, waiting beats failing the upload.
            Duration wait = shortestRateLimit[0];
            if (wait == null || wait.compareTo(MAX_WAIT) > 0) throw e;
            log.info("All '{}' providers rate-limited; waiting {} and retrying once", pool, wait);
            try {
                Thread.sleep(wait.plusSeconds(1));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw e;
            }
            return attempt(pool, system, user, temperature, new Duration[]{null});
        }
    }

    private JsonNode attempt(String pool, String system, String user, double temperature, Duration[] shortestRateLimit) {
        List<LlmProperties.Provider> providers = props.pools().getOrDefault(pool, List.of());
        List<String> reasons = new ArrayList<>();
        for (var p : providers) {
            Long ttl = redis.getExpire(p.cooldownKey());
            if (ttl != null && ttl > 0 && "429".equals(redis.opsForValue().get(p.cooldownKey())))
                shortestRateLimit[0] = min(shortestRateLimit[0], Duration.ofSeconds(ttl));
            if (p.apiKey() == null || p.apiKey().isBlank()) { reasons.add(p.name() + ": no key"); continue; }
            if (Boolean.TRUE.equals(redis.hasKey(p.cooldownKey()))) { reasons.add(p.name() + ": cooling down"); continue; }
            try {
                JsonNode result = call(p, system, user, temperature);
                if (result != null) return result;
                reasons.add(p.name() + ": reply was not a JSON object");
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                // "limit: 0" = this model has no free quota on this key; waiting will never help
                boolean noQuota = status == 429 && e.getResponseBodyAsString().contains("limit: 0");
                Duration cool = noQuota ? BAD_KEY : status == 429 ? retryAfter(e) : status >= 500 ? SERVER_ERROR
                        : status == 401 || status == 403 ? BAD_KEY : null; // other 4xx (e.g. 413 too large): just move on
                if (cool != null) redis.opsForValue().set(p.cooldownKey(), noQuota ? "no-quota" : String.valueOf(status), cool);
                if (status == 429 && !noQuota) shortestRateLimit[0] = min(shortestRateLimit[0], cool);
                reasons.add(p.name() + ": HTTP " + status);
                log.warn("LLM {} {} -> HTTP {}, cooldown {}", p.name(), p.model(), status, cool);
            } catch (ResourceAccessException e) {
                redis.opsForValue().set(p.cooldownKey(), "timeout", TIMED_OUT);
                reasons.add(p.name() + ": " + e.getMessage());
                log.warn("LLM {} {} -> {}", p.name(), p.model(), e.getMessage());
            }
        }
        throw new AllProvidersFailedException(pool, reasons);
    }

    private JsonNode call(LlmProperties.Provider p, String system, String user, double temperature) {
        Map<String, Object> body = Map.of(
                "model", p.model(),
                "temperature", temperature,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
        String raw = http.post().uri(p.baseUrl() + "/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + p.apiKey())
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(String.class); // parsed by hand: some gateways send JSON with a wrong content type
        try {
            String content = json.readTree(raw == null ? "{}" : raw).path("choices").path(0).path("message").path("content").asText("");
            JsonNode parsed = json.readTree(FENCE.matcher(content.strip()).replaceAll(""));
            if (parsed != null && parsed.isArray() && parsed.size() == 1) parsed = parsed.get(0); // Gemini lite wraps it as [{...}]
            return parsed != null && parsed.isObject() ? parsed : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Duration min(Duration a, Duration b) {
        return a == null || b.compareTo(a) < 0 ? b : a;
    }

    /** Retry-After header (seconds), else Gemini's body "retryDelay", else a default. */
    static Duration retryAfter(RestClientResponseException e) {
        Duration d = RATE_LIMIT_DEFAULT;
        String header = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        Matcher m = RETRY_DELAY.matcher(e.getResponseBodyAsString());
        if (header != null && header.strip().matches("\\d+")) d = Duration.ofSeconds(Long.parseLong(header.strip()));
        else if (m.find()) d = Duration.ofSeconds(Long.parseLong(m.group(1)));
        if (d.isZero()) d = Duration.ofSeconds(1);
        return d.compareTo(MAX_COOLDOWN) > 0 ? MAX_COOLDOWN : d;
    }
}
