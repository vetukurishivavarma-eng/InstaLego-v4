package in.legalverify;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/** mock = instant unlock for testing; razorpay = real order + signature check (test or live keys). */
@Service
public class PaymentService {
    public final String keyId;
    public final int priceInr;
    private final boolean mock;
    private final String keySecret;
    private final RestClient razorpay;

    public PaymentService(@Value("${payment.mode}") String mode, @Value("${payment.key-id}") String keyId,
                          @Value("${payment.key-secret}") String keySecret, @Value("${payment.price-inr}") int priceInr,
                          RestClient.Builder builder) {
        this.mock = !"razorpay".equalsIgnoreCase(mode);
        this.keyId = keyId;
        this.keySecret = keySecret;
        this.priceInr = priceInr;
        this.razorpay = builder.baseUrl("https://api.razorpay.com/v1").defaultHeaders(h -> h.setBasicAuth(keyId, keySecret)).build();
    }

    public boolean isMock() {
        return mock;
    }

    /** Returns the Razorpay order id (null in mock mode). */
    public String createOrder(String caseId) {
        if (mock) return null;
        JsonNode order = razorpay.post().uri("/orders").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("amount", priceInr * 100, "currency", "INR", "receipt", caseId))
                .retrieve().body(JsonNode.class);
        return order.get("id").asText();
    }

    public boolean verify(String orderId, String paymentId, String signature) {
        if (mock) return true;
        if (orderId == null || paymentId == null || signature == null) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(mac.doFinal((orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }
}
