package in.legalverify;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@ConfigurationProperties("llm")
public record LlmProperties(Duration timeout, Map<String, List<Provider>> pools) {
    public record Provider(String name, String baseUrl, String apiKey, String model) {
        String cooldownKey() {
            return "llm:cooldown:" + name + ":" + model;
        }
    }
}
