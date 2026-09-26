package ages.vstable.backend.external.avenia;

import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteRequest;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteResponse;
import ages.vstable.backend.external.avenia.dto.AveniaTicketRequest;
import ages.vstable.backend.external.avenia.dto.AveniaTicketResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.security.PrivateKey;
import java.time.Instant;

@Component
public class AveniaClient implements AveniaGateway {

    private static final String QUOTE_URI = "/v2/account/quote/fixed-rate";
    private static final String TICKETS_URI = "/v2/account/tickets/";

    private final AveniaProperties properties;
    private final AveniaRequestSigner requestSigner;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private volatile PrivateKey privateKey;

    public AveniaClient(
            AveniaProperties properties,
            AveniaRequestSigner requestSigner,
            ObjectMapper objectMapper,
            RestClient.Builder restClientBuilder
    ) {
        this.properties = properties;
        this.requestSigner = requestSigner;
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilder.baseUrl(properties.getBaseUrl()).build();
    }

    @Override
    public AveniaQuoteResponse createQuote(AveniaQuoteRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("quote request must be provided");
        }
        String uri = buildQuoteUri(request);
        return get(uri, AveniaQuoteResponse.class, "Could not obtain a quote from Avenia");
    }

    @Override
    public AveniaTicketResponse createTicket(AveniaTicketRequest request) {
        validateTicket(request);
        String uri = appendSubAccount(TICKETS_URI, request.getSubAccountId());
        String body = serialize(request);
        return post(uri, body, AveniaTicketResponse.class, "Could not create the Avenia ticket");
    }

    private String buildQuoteUri(AveniaQuoteRequest request) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(QUOTE_URI)
                .queryParam(AveniaApi.Quote.INPUT_CURRENCY, request.inputCurrency())
                .queryParam(AveniaApi.Quote.INPUT_PAYMENT_METHOD, request.inputPaymentMethod())
                .queryParam(AveniaApi.Quote.OUTPUT_CURRENCY, request.outputCurrency())
                .queryParam(AveniaApi.Quote.OUTPUT_PAYMENT_METHOD, request.outputPaymentMethod())
                .queryParam(AveniaApi.Quote.INPUT_THIRD_PARTY, request.inputThirdParty())
                .queryParam(AveniaApi.Quote.OUTPUT_THIRD_PARTY, request.outputThirdParty());

        addQueryParam(builder, AveniaApi.Quote.INPUT_AMOUNT, request.inputAmount());
        addQueryParam(builder, AveniaApi.Quote.OUTPUT_AMOUNT, request.outputAmount());
        addQueryParam(builder, AveniaApi.Quote.BLOCKCHAIN_SEND_METHOD, request.blockchainSendMethod());
        addQueryParam(builder, AveniaApi.Quote.MARKUP_FLOATING_FEE, request.markupFloatingFee());
        addQueryParam(builder, AveniaApi.Quote.MARKUP_INPUT_FIXED_FEE, request.markupInputFixedFee());
        addQueryParam(builder, AveniaApi.Quote.MARKUP_OUTPUT_FIXED_FEE, request.markupOutputFixedFee());
        addQueryParam(builder, AveniaApi.Quote.MARKUP_CURRENCY, request.markupCurrency());
        addQueryParam(builder, AveniaApi.Quote.TICKET_REFUND_ID, request.ticketRefundId());
        addQueryParam(builder, AveniaApi.Quote.OUTPUT_BR_CODE, request.outputBrCode());
        addQueryParam(builder, AveniaApi.SubAccount.SUB_ACCOUNT_ID, request.subAccountId());

        return builder.build().encode().toUriString();
    }

    private void addQueryParam(UriComponentsBuilder builder, String name, Object value) {
        if (value != null && (!(value instanceof String text) || !text.isBlank())) {
            builder.queryParam(name, value);
        }
    }

    private String appendSubAccount(String uri, String subAccountId) {
        if (subAccountId == null || subAccountId.isBlank()) {
            return uri;
        }
        return UriComponentsBuilder.fromPath(uri)
                .queryParam(AveniaApi.SubAccount.SUB_ACCOUNT_ID, subAccountId)
                .build()
                .encode()
                .toUriString();
    }

    private <T> T get(String uri, Class<T> responseType, String errorMessage) {
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        String signature = sign(timestamp, "GET", uri, "");

        try {
            return restClient.get()
                    .uri(absoluteUri(uri))
                    .header("X-API-Key", apiKey())
                    .header("X-API-Timestamp", timestamp)
                    .header("X-API-Signature", signature)
                    .retrieve()
                    .body(responseType);
        } catch (RestClientResponseException ex) {
            throw providerError(errorMessage, ex);
        } catch (RestClientException ex) {
            throw AveniaIntegrationException.communication(errorMessage, ex);
        }
    }

    private <T> T post(String uri, String body, Class<T> responseType, String errorMessage) {
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        String signature = sign(timestamp, "POST", uri, body);

        try {
            return restClient.post()
                    .uri(absoluteUri(uri))
                    .header("X-API-Key", apiKey())
                    .header("X-API-Timestamp", timestamp)
                    .header("X-API-Signature", signature)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(responseType);
        } catch (RestClientResponseException ex) {
            throw providerError(errorMessage, ex);
        } catch (RestClientException ex) {
            throw AveniaIntegrationException.communication(errorMessage, ex);
        }
    }

    private String sign(String timestamp, String method, String uri, String body) {
        try {
            return requestSigner.sign(timestamp, method, uri, body, privateKey());
        } catch (IllegalStateException ex) {
            throw AveniaIntegrationException.configuration("Could not sign the Avenia request", ex);
        }
    }

    private URI absoluteUri(String uri) {
        String baseUrl = properties.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw AveniaIntegrationException.configuration("AVENIA_BASE_URL is not configured", null);
        }
        return URI.create((baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl) + uri);
    }

    private String apiKey() {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw AveniaIntegrationException.configuration("AVENIA_API_KEY is not configured", null);
        }
        return properties.getApiKey();
    }

    private PrivateKey privateKey() {
        if (privateKey == null) {
            synchronized (this) {
                if (privateKey == null) {
                    try {
                        privateKey = PemPrivateKeyLoader.load(properties.getPrivateKey());
                    } catch (IllegalArgumentException ex) {
                        throw AveniaIntegrationException.configuration(ex.getMessage(), ex);
                    }
                }
            }
        }
        return privateKey;
    }

    private String serialize(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            throw AveniaIntegrationException.configuration("Could not serialize the Avenia request", ex);
        }
    }

    private void validateTicket(AveniaTicketRequest request) {
        if (request == null || request.getQuoteToken() == null || request.getQuoteToken().isBlank()) {
            throw new IllegalArgumentException("quoteToken must be provided");
        }
        if (request.getCustomDuration() != null
                && (request.getCustomDuration() < AveniaApi.Ticket.CUSTOM_DURATION_MIN_SECONDS
                || request.getCustomDuration() > AveniaApi.Ticket.CUSTOM_DURATION_MAX_SECONDS_NO_CONVERSION)) {
            throw new IllegalArgumentException("customDuration must be between 300 and 259200 seconds");
        }
    }

    private AveniaIntegrationException providerError(String message, RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        return AveniaIntegrationException.response(message + ": " + safeErrorDetail(exception), status, exception);
    }

    private String safeErrorDetail(RestClientResponseException exception) {
        String status = "HTTP " + exception.getStatusCode().value();
        String body = exception.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return status;
        }

        try {
            JsonNode root = objectMapper.readTree(body);
            for (String field : new String[]{"message", "error", "detail", "errorMessage"}) {
                JsonNode value = root.get(field);
                if (value != null && value.isTextual() && !value.asText().isBlank()) {
                    return status + " - " + value.asText();
                }
            }
        } catch (JsonProcessingException ignored) {
            // Non-JSON provider bodies are reduced to the HTTP status.
        }
        return status;
    }
}
