package ages.vstable.backend.sandbox;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Test-only boundary: serial requests, exact origin/instance, stop conditions, allowlist export. */
final class BlindPaySandboxBoundary implements ClientHttpRequestInterceptor {
    static final int MAX_CALLS = 180;
    static final Duration MAX_DURATION = Duration.ofMinutes(25);
    static final long MIN_INTERVAL_MS = 3_000;
    private final JsonMapper json = JsonMapper.builder().build();
    private final String instance;
    private final int priorCalls;
    private final List<Map<String, Object>> calls = new ArrayList<>();
    private Instant started;
    private long lastCall;
    private boolean development;
    private String stopped;
    private String scenario = "preflight";
    private Runnable checkpoint = () -> { };
    // Raw content is held only in process memory, never exported.
    private JsonNode lastResponse;
    private final Map<String, JsonNode> quoteResponses = new LinkedHashMap<>();
    private final Map<String, JsonNode> quoteRequests = new LinkedHashMap<>();

    BlindPaySandboxBoundary(String instance) {
        this.instance = instance;
        priorCalls = Integer.parseInt(System.getenv().getOrDefault("BLINDPAY_SECURITY_PRIOR_CALLS", "0"));
    }
    void scenario(String id) { scenario = id; }
    void checkpoint(Runnable action) { checkpoint = action; }
    void developmentConfirmed() { development = true; }
    boolean halted() { return stopped != null; }
    int count() { return priorCalls + calls.size(); }
    int priorCalls() { return priorCalls; }
    int currentCalls() { return calls.size(); }
    String stopReason() { return stopped == null ? "none" : stopped; }
    List<Map<String, Object>> calls() { return List.copyOf(calls); }
    JsonNode lastResponse() { return lastResponse; }
    JsonNode quoteResponse(String id) { return quoteResponses.get(id); }
    JsonNode quoteRequest(String id) { return quoteRequests.get(id); }

    @Override
    public synchronized ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                                     ClientHttpRequestExecution execution) throws IOException {
        var uri = request.getURI();
        String root = "/v1/instances/" + instance;
        boolean exactInstance = uri.getPath().equals(root) || uri.getPath().startsWith(root + "/");
        if (!"https".equals(uri.getScheme()) || !"api.blindpay.com".equals(uri.getHost())
                || (uri.getPort() != -1 && uri.getPort() != 443) || !exactInstance
                || uri.getRawQuery() != null || uri.getUserInfo() != null
                || !(request.getMethod() == HttpMethod.GET || request.getMethod() == HttpMethod.POST)) {
            stop("OUTBOUND_SCOPE_DENIED");
        }
        if (!development && !(request.getMethod() == HttpMethod.GET && uri.getPath().equals(root))) {
            stop("DEVELOPMENT_NOT_CONFIRMED");
        }
        if (stopped != null) { throw new IOException("Sandbox stopped: " + stopped); }
        if (started == null) { started = Instant.now(); }
        if (count() >= MAX_CALLS || Instant.now().isAfter(started.plus(MAX_DURATION))) {
            stop("BUDGET_EXHAUSTED");
        }
        pause(Math.max(0, MIN_INTERVAL_MS - (System.currentTimeMillis() - lastCall)));
        lastCall = System.currentTimeMillis();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("sequence", calls.size() + 1);
        entry.put("budget_sequence", count() + 1);
        entry.put("scenario", scenario);
        entry.put("method", request.getMethod().name());
        entry.put("operation", uri.getPath().substring(root.length()).replaceAll("/[A-Za-z]{2}_[A-Za-z0-9]+", "/{id}"));
        entry.put("bearer_present", request.getHeaders().containsHeader(HttpHeaders.AUTHORIZATION));
        entry.put("idempotency_present", request.getHeaders().containsHeader("Idempotency-Key"));
        calls.add(entry);
        long before = System.nanoTime();
        try {
            ClientHttpResponse response = execution.execute(request, body);
            byte[] content = response.getBody().readAllBytes();
            int status = response.getStatusCode().value();
            entry.put("http_status", status);
            entry.put("duration_ms", Duration.ofNanos(System.nanoTime() - before).toMillis());
            entry.put("json_content_type", response.getHeaders().getContentType() != null
                    && response.getHeaders().getContentType().toString().contains("json"));
            try {
                lastResponse = json.readTree(content);
                JsonNode input = body.length == 0 ? null : json.readTree(body);
                entry.put("request", financialFields(input));
                entry.put("response", financialFields(lastResponse));
                if (lastResponse.isObject() && lastResponse.has("id")
                        && (uri.getPath().endsWith("/quotes") || uri.getPath().endsWith("/payin-quotes"))) {
                    String id = lastResponse.get("id").asString();
                    quoteResponses.put(id, lastResponse);
                    quoteRequests.put(id, input);
                }
                if (status >= 400 && lastResponse.has("code")) {
                    String code = lastResponse.get("code").asString();
                    if (code.matches("[A-Z0-9_]{1,80}")) { entry.put("error_code", code); }
                }
            } catch (RuntimeException ignored) { entry.put("json_parseable", false); }
            if (status == 429 || status >= 500 || status == 401 || status == 403) {
                stopped = "PROVIDER_HTTP_" + status;
            }
            return new BufferedResponse(response, content);
        } catch (IOException | RuntimeException ex) {
            stopped = "TRANSPORT_RESULT_UNKNOWN";
            entry.put("transport_failure", ex.getClass().getSimpleName());
            throw new IOException("Sandbox transport stopped; inspect sanitized evidence");
        } finally {
            checkpoint.run();
        }
    }

    private Map<String, Object> financialFields(JsonNode node) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (node == null || !node.isObject()) { return fields; }
        for (String name : List.of("request_amount", "sender_amount", "receiver_amount", "flat_fee",
                "billing_fee_amount", "partner_fee_amount", "transaction_fee_amount", "expires_at")) {
            JsonNode value = node.get(name);
            if (value != null && value.isNumber()) { fields.put(name, value.decimalValue().toPlainString()); }
        }
        for (String name : List.of("token", "network", "currency", "currency_type", "payment_method", "status")) {
            JsonNode value = node.get(name);
            if (value != null && value.isString() && value.asString().matches("[A-Za-z0-9_]{1,32}")) {
                fields.put(name, value.asString());
            }
        }
        if (node.has("cover_fees") && node.get("cover_fees").isBoolean()) {
            fields.put("cover_fees", node.get("cover_fees").asBoolean());
        }
        return fields;
    }

    private void stop(String reason) throws IOException {
        stopped = reason;
        throw new IOException("Sandbox stopped: " + reason);
    }

    static void pause(long millis) throws IOException {
        try { Thread.sleep(millis); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException("Interrupted"); }
    }

    private record BufferedResponse(ClientHttpResponse delegate, byte[] content) implements ClientHttpResponse {
        @Override public HttpStatusCode getStatusCode() throws IOException { return delegate.getStatusCode(); }
        @Override public String getStatusText() throws IOException { return delegate.getStatusText(); }
        @Override public HttpHeaders getHeaders() { return delegate.getHeaders(); }
        @Override public InputStream getBody() { return new ByteArrayInputStream(content); }
        @Override public void close() { delegate.close(); }
    }
}
