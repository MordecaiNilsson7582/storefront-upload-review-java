package example.checkout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.moderations.ModerationCreateParams;
import com.openai.models.moderations.ModerationImageUrlInput;
import com.openai.models.moderations.ModerationMultiModalInput;
import com.openai.models.moderations.ModerationTextInput;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class UploadReview {
    private final OpenAIClient ai;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private final String key;
    private final String baseUrl;

    public UploadReview(@Value("${review.base-url}") String baseUrl,
                        @Value("${review.ai-base-url}") String aiBaseUrl) {
        this.key = System.getenv("INFRAI_API_KEY");
        if (key == null || key.isBlank()) throw new IllegalStateException("Set INFRAI_API_KEY");
        this.baseUrl = baseUrl;
        this.ai = OpenAIOkHttpClient.builder().apiKey(key).baseUrl(aiBaseUrl).build();
    }

    public record Submission(String orderId, String caption, String imageBase64, String mimeType) {}
    public record Receipt(String orderId, String status, JsonNode image) {}

    static String decision(boolean flagged) { return flagged ? "QUARANTINED" : "READY_FOR_FULFILLMENT"; }

    @PostMapping("/orders/review")
    public Receipt review(@RequestBody Submission request) throws Exception {
        if (request.orderId() == null || request.orderId().isBlank() || request.caption() == null
                || request.imageBase64() == null || request.mimeType() == null
                || !List.of("image/jpeg", "image/png", "image/webp").contains(request.mimeType())) {
            throw new IllegalArgumentException("orderId, caption, imageBase64 and a supported mimeType are required");
        }
        byte[] bytes = Base64.getDecoder().decode(request.imageBase64());
        if (bytes.length > 5_000_000) throw new IllegalArgumentException("Image exceeds 5 MB");
        String imageUrl = "data:" + request.mimeType() + ";base64," + request.imageBase64();
        var input = List.of(
            ModerationMultiModalInput.ofText(ModerationTextInput.builder().text(request.caption()).build()),
            ModerationMultiModalInput.ofImageUrl(ModerationImageUrlInput.builder()
                .imageUrl(ModerationImageUrlInput.ImageUrl.builder().url(imageUrl).build()).build()));
        var moderation = ai.moderations().create(ModerationCreateParams.builder()
            .model("omni-moderation-latest").inputOfModerationMultiModalArray(input).build());
        if (moderation.results().stream().anyMatch(result -> result.flagged())) {
            return new Receipt(request.orderId(), decision(true), null);
        }
        JsonNode transformed = resize(request.imageBase64(), request.orderId());
        return new Receipt(request.orderId(), decision(false), transformed);
    }

    private JsonNode resize(String image, String orderId) throws Exception {
        byte[] body = json.writeValueAsBytes(Map.of("image", Map.of("base64", image), "width", 1200, "height", 1200,
            "fit", "contain", "store", false, "idempotency_key", orderId + ":resize"));
        for (int attempt = 0; attempt < 4; attempt++) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/image/resize"))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .method("POST", HttpRequest.BodyPublishers.ofByteArray(body)).build();
            HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode envelope = json.readTree(response.body());
            if (!envelope.path("ok").asBoolean(false)) {
                if (response.statusCode() == 429 && attempt < 3) {
                    long delay = response.headers().firstValue("Retry-After").map(value -> {
                        try { return Long.parseLong(value) * 1000; }
                        catch (NumberFormatException ignored) { return 0L; }
                    }).orElse(0L);
                    Thread.sleep(Math.max(delay, 500L << attempt));
                    continue;
                }
                throw new InfraiFailure(response.statusCode(), envelope.path("error").toString());
            }
            return envelope.path("data");
        }
        throw new IllegalStateException("Retry budget exhausted");
    }

    static class InfraiFailure extends RuntimeException {
        final int status;
        InfraiFailure(int status, String detail) { super(detail); this.status = status; }
    }

    @ExceptionHandler(InfraiFailure.class)
    ResponseEntity<Map<String, String>> upstreamError(InfraiFailure error) {
        int status = error.status >= 400 && error.status < 500 ? error.status : 502;
        return ResponseEntity.status(status).body(Map.of("error", error.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class})
    ResponseEntity<Map<String, String>> invalidInput(Exception error) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", error.getMessage()));
    }

    public static void main(String[] args) { SpringApplication.run(UploadReview.class, args); }
}
