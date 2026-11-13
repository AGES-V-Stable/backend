package ages.vstable.backend.sandbox;

import ages.vstable.backend.external.avenia.AveniaClient;
import ages.vstable.backend.external.avenia.AveniaProperties;
import ages.vstable.backend.external.avenia.AveniaRequestSigner;
import ch.qos.logback.classic.Level;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Test-only boundary. No arbitrary hosts, automatic redirects, retries or raw HTTP exports. */
final class AveniaSandboxSession {
    static final String ORIGIN = "https://api.sandbox.avenia.io:10952";
    private static final Set<String> READ_PATHS = Set.of(
            "/v2/account/access-info", "/v2/account/account-info", "/v2/account/metadata",
            "/v2/account/limits", "/v2/notifications/webhooks/", "/v2/account/sub-accounts",
            "/v2/account/quote/fixed-rate", "/v2/account/tickets/");
    final JsonMapper json = JsonMapper.builder().build();
    final AveniaProperties properties = new AveniaProperties();
    final Map<String, Object> evidence = new LinkedHashMap<>();
    final List<Map<String, Object>> calls = new ArrayList<>();
    final List<Map<String, Object>> cases = new ArrayList<>();
    final Path output;
    final Instant started = Instant.now();
    final String phase = System.getProperty("avenia.assessment.phase", "preflight");
    final int callLimit = Math.min(phase.equals("preflight") ? 8 : 42,
            Integer.parseInt(System.getenv().getOrDefault("AVENIA_ASSESSMENT_REMAINING_CALLS", "0")));
    final AveniaClient client;
    boolean accountConfirmed;
    boolean writesAllowed;
    boolean halted;
    int serverErrors = Integer.parseInt(System.getenv().getOrDefault("AVENIA_ASSESSMENT_PRIOR_SERVER_ERRORS", "0"));
    long lastCall;

    AveniaSandboxSession() throws IOException {
        if (!"true".equals(System.getenv("AVENIA_SECURITY_SANDBOX_ENABLED"))) {
            throw new IllegalStateException("Manual sandbox opt-in absent");
        }
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("ROOT")).setLevel(Level.OFF);
        output = Path.of(System.getenv("AVENIA_ASSESSMENT_OUTPUT"));
        Files.createDirectories(output);
        if (Files.exists(output.resolve("assessment.json"))) {
            throw new IllegalStateException("Existing execution: review checkpoint; never overwrite or resend");
        }
        Path credentials = Path.of(System.getenv("AVENIA_ASSESSMENT_KEY_DIR"));
        properties.setBaseUrl(ORIGIN);
        properties.setApiKey(Files.readString(credentials.resolve("api_key.txt")).trim());
        properties.setPrivateKey(Files.readString(credentials.resolve("private_key.pem")));
        client = new AveniaClient(properties, new AveniaRequestSigner(), json, builder());
        evidence.put("schema", 1);
        evidence.put("issue", "AGES-V-Stable/cicd#85");
        evidence.put("branch", System.getenv("AVENIA_ASSESSMENT_BRANCH"));
        evidence.put("commit", System.getenv("AVENIA_ASSESSMENT_COMMIT"));
        evidence.put("environment", "local backend / Avenia sandbox only");
        evidence.put("phase", phase);
        evidence.put("started_utc", started.toString());
        evidence.put("call_limit", callLimit);
        evidence.put("time_limit_minutes", phase.equals("preflight") ? 5 : 15);
        evidence.put("previous_preparation_calls", 2);
        evidence.put("historical_calls_note", "Two successful key-validation GETs precede this assessment; not reclassified as new tests");
        evidence.put("calls", calls);
        evidence.put("cases", cases);
        evidence.put("state", "RUNNING");
        checkpoint();
    }

    RestClient.Builder builder() {
        var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(25));
        return RestClient.builder().requestFactory(factory).requestInterceptor(interceptor());
    }

    static void validateTarget(URI uri, HttpMethod method) {
        if (!ORIGIN.equals(uri.getScheme() + "://" + uri.getRawAuthority())
                || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalStateException("Sandbox origin boundary rejected target");
        }
        boolean get = method == HttpMethod.GET && (READ_PATHS.contains(uri.getPath())
                || uri.getPath().matches("/v2/account/tickets/[a-fA-F0-9-]{36}"));
        boolean post = method == HttpMethod.POST && Set.of("/v2/account/sub-accounts",
                "/v2/account/tickets/", "/v2/account/beneficiaries/wallets/").contains(uri.getPath());
        if (!get && !post) throw new IllegalStateException("Method/path outside sandbox assessment");
    }

    ClientHttpRequestInterceptor interceptor() {
        return (request, body, execution) -> {
            validateTarget(request.getURI(), request.getMethod());
            int limit = callLimit;
            long minutes = phase.equals("preflight") ? 5 : 15;
            if (halted || calls.size() >= limit || Instant.now().isAfter(started.plus(Duration.ofMinutes(minutes)))) {
                throw new IllegalStateException("Sandbox budget or stop condition reached");
            }
            if (request.getMethod() != HttpMethod.GET && (!accountConfirmed || !writesAllowed)) {
                throw new IllegalStateException("Write prerequisites not confirmed");
            }
            long wait = 3000 - (System.currentTimeMillis() - lastCall);
            if (wait > 0) try { Thread.sleep(wait); } catch (InterruptedException ex) {
                Thread.currentThread().interrupt(); throw new IOException("Interrupted before HTTP attempt");
            }
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("sequence", calls.size() + 1);
            call.put("method", request.getMethod().name());
            call.put("endpoint", request.getURI().getPath().replaceAll("[a-fA-F0-9-]{36}", "{OWN_TICKET}"));
            call.put("started_utc", Instant.now().toString());
            call.put("state", "REQUEST_STARTED_RESULT_UNKNOWN");
            call.put("rsa_auth_headers_present", request.getHeaders().getFirst("X-API-Key") != null
                    && request.getHeaders().getFirst("X-API-Timestamp") != null
                    && request.getHeaders().getFirst("X-API-Signature") != null);
            calls.add(call);
            checkpoint(); // Durable intent before the request can reach the provider.
            if (request.getMethod() == HttpMethod.POST) {
                JsonNode payload = json.readTree(body);
                Map<String, Object> intent = new LinkedHashMap<>();
                intent.put("started_utc", call.get("started_utc"));
                intent.put("endpoint", call.get("endpoint"));
                for (String field : List.of("externalId", "name", "alias")) {
                    if (payload.hasNonNull(field)) intent.put(field, payload.path(field).asString());
                }
                String query = request.getURI().getRawQuery();
                if (query != null) for (String part : query.split("&")) {
                    if (part.startsWith("subAccountId=")) intent.put("subaccount", part.substring("subAccountId=".length()));
                }
                Path journal = Path.of(System.getenv("AVENIA_ASSESSMENT_KEY_DIR"),
                        "request-intent-" + started.toEpochMilli() + "-" + calls.size() + ".json");
                Files.writeString(journal, json.writeValueAsString(intent));
            }
            lastCall = System.currentTimeMillis();
            try {
                var response = execution.execute(request, body);
                int status = response.getStatusCode().value();
                call.put("http_status", status);
                call.put("duration_ms", System.currentTimeMillis() - lastCall);
                call.put("state", "RESPONSE_RECEIVED");
                if (status == 429 || status >= 300 && status < 400 || status >= 500 && ++serverErrors >= 2) halted = true;
                checkpoint();
                return response;
            } catch (IOException | RuntimeException ex) {
                call.put("state", "RESULT_UNKNOWN");
                call.put("error_type", ex.getClass().getSimpleName());
                halted = true;
                checkpoint();
                throw new IllegalStateException("HTTP result unknown; stop and reconcile without resubmission");
            }
        };
    }

    JsonNode read(String path) {
        // There is no public account/ticket-read method on AveniaGateway at this HEAD.
        // Reuse the production GET transport/signature, while explicitly identifying this test adapter.
        return ReflectionTestUtils.invokeMethod(client, "get", path, JsonNode.class, "Assessment read failed");
    }

    void record(String id, String status, String requirement, String expected, String observed,
                String layer, Map<String, ?> details, int before) throws IOException {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id); item.put("status", status); item.put("requirement", requirement);
        item.put("expected", expected); item.put("observed", observed); item.put("layer", layer);
        item.put("details", details); item.put("calls_consumed", calls.size() - before);
        item.put("recorded_utc", Instant.now().toString());
        cases.add(item); checkpoint();
    }

    void checkpoint() throws IOException {
        evidence.put("last_checkpoint_utc", Instant.now().toString());
        evidence.put("calls_attempted", calls.size());
        Path temporary = output.resolve("assessment.next");
        Files.writeString(temporary, json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        Files.move(temporary, output.resolve("assessment.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    void finish(String state) throws IOException {
        evidence.put("state", state); evidence.put("finished_utc", Instant.now().toString()); checkpoint();
    }

    static String fingerprint(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (Exception ex) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    static Map<String, Object> permissionFlags(JsonNode root) {
        Map<String, Object> result = new LinkedHashMap<>();
        collectFlags(root, result);
        return result;
    }

    static String validationField(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() == null) continue;
            var match = java.util.regex.Pattern.compile("InvalidFieldError:\\s*([A-Za-z][A-Za-z.]*)\\b")
                    .matcher(cause.getMessage());
            if (!match.find()) continue;
            String field = match.group(1);
            if (Set.of("isSameEconomicGroup", "counterpartyCountry", "counterpartyName", "walletChain",
                    "walletAddress", "isSelfCustody", "custodianName", "subAccountId", "quoteToken").contains(field)) return field;
        }
        return null;
    }

    private static void collectFlags(JsonNode node, Map<String, Object> result) {
        if (node.isObject()) {
            for (String key : List.of("admin", "viewOnly", "convert", "payIn", "payOut", "onChain", "beneficiary", "enforceAllowedBeneficiaries")) {
                if (node.path(key).isBoolean()) result.put(key, node.path(key).asBoolean());
            }
        }
        if (node.isObject() || node.isArray()) for (JsonNode child : node) collectFlags(child, result);
    }
}
