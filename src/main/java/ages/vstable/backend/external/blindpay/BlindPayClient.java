package ages.vstable.backend.external.blindpay;

import ages.vstable.backend.exception.BlindPayIntegrationException;
import ages.vstable.backend.external.blindpay.dto.BlindPayBankAccountResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayCreateBankAccountRequest;
import ages.vstable.backend.external.blindpay.dto.BlindPayCreateCustomerRequest;
import ages.vstable.backend.external.blindpay.dto.BlindPayCustomerCreatedResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayCustomerResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayErrorResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayEvmPayoutRequest;
import ages.vstable.backend.external.blindpay.dto.BlindPayPayinQuoteRequest;
import ages.vstable.backend.external.blindpay.dto.BlindPayPayinQuoteResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayPayinResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayPayoutResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayQuoteRequest;
import ages.vstable.backend.external.blindpay.dto.BlindPayQuoteResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.Map;

/**
 * Client HTTP da BlindPay (https://api.blindpay.com). Autentica com Bearer token,
 * resolve o {@code instance_id} das propriedades e traduz qualquer falha em
 * {@link BlindPayIntegrationException} sem propagar o corpo da resposta, que pode
 * conter dados pessoais ou financeiros.
 *
 * <p>Corpos são (de)serializados com o {@link ObjectMapper} da aplicação (Jackson 3),
 * garantindo que os {@code @JsonNaming} dos DTOs sejam respeitados.
 */
@Slf4j
@Component
public class BlindPayClient implements BlindPayGateway {

    private static final String INSTANCE_PATH = "/v1/instances/{instanceId}";
    private static final String CUSTOMERS_PATH = INSTANCE_PATH + "/customers";
    private static final String CUSTOMER_PATH = CUSTOMERS_PATH + "/{customerId}";
    private static final String BANK_ACCOUNTS_PATH = CUSTOMER_PATH + "/bank-accounts";
    private static final String BANK_ACCOUNT_PATH = BANK_ACCOUNTS_PATH + "/{bankAccountId}";
    private static final String QUOTES_PATH = INSTANCE_PATH + "/quotes";
    private static final String EVM_PAYOUTS_PATH = INSTANCE_PATH + "/payouts/evm";
    private static final String PAYOUT_PATH = INSTANCE_PATH + "/payouts/{payoutId}";
    private static final String PAYIN_QUOTES_PATH = INSTANCE_PATH + "/payin-quotes";
    private static final String EVM_PAYINS_PATH = INSTANCE_PATH + "/payins/evm";
    private static final String PAYIN_PATH = INSTANCE_PATH + "/payins/{payinId}";

    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 255;

    private final BlindPayProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public BlindPayClient(
            BlindPayProperties properties,
            ObjectMapper objectMapper,
            RestClient.Builder restClientBuilder
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilder.build();
    }

    @Override
    public BlindPayCustomerCreatedResponse createCustomer(
            BlindPayCreateCustomerRequest request, String idempotencyKey) {
        requireRequest(request, "customer request");
        return post("Could not create the BlindPay customer", request, idempotencyKey,
                BlindPayCustomerCreatedResponse.class, CUSTOMERS_PATH);
    }

    @Override
    public BlindPayCustomerResponse getCustomer(String customerId) {
        requireId(customerId, "customerId");
        return get("Could not fetch the BlindPay customer",
                BlindPayCustomerResponse.class, CUSTOMER_PATH, customerId);
    }

    @Override
    public BlindPayBankAccountResponse createBankAccount(
            String customerId, BlindPayCreateBankAccountRequest request, String idempotencyKey) {
        requireId(customerId, "customerId");
        requireRequest(request, "bank account request");
        return post("Could not create the BlindPay bank account", request, idempotencyKey,
                BlindPayBankAccountResponse.class, BANK_ACCOUNTS_PATH, customerId);
    }

    @Override
    public BlindPayBankAccountResponse getBankAccount(String customerId, String bankAccountId) {
        requireId(customerId, "customerId");
        requireId(bankAccountId, "bankAccountId");
        return get("Could not fetch the BlindPay bank account",
                BlindPayBankAccountResponse.class, BANK_ACCOUNT_PATH, customerId, bankAccountId);
    }

    @Override
    public BlindPayQuoteResponse createQuote(BlindPayQuoteRequest request, String idempotencyKey) {
        requireRequest(request, "quote request");
        return post("Could not create the BlindPay payout quote", request, idempotencyKey,
                BlindPayQuoteResponse.class, QUOTES_PATH);
    }

    @Override
    public BlindPayPayoutResponse createEvmPayout(BlindPayEvmPayoutRequest request, String idempotencyKey) {
        requireRequest(request, "payout request");
        return post("Could not create the BlindPay payout", request, idempotencyKey,
                BlindPayPayoutResponse.class, EVM_PAYOUTS_PATH);
    }

    @Override
    public BlindPayPayoutResponse getPayout(String payoutId) {
        requireId(payoutId, "payoutId");
        return get("Could not fetch the BlindPay payout",
                BlindPayPayoutResponse.class, PAYOUT_PATH, payoutId);
    }

    @Override
    public BlindPayPayinQuoteResponse createPayinQuote(BlindPayPayinQuoteRequest request, String idempotencyKey) {
        requireRequest(request, "payin quote request");
        return post("Could not create the BlindPay payin quote", request, idempotencyKey,
                BlindPayPayinQuoteResponse.class, PAYIN_QUOTES_PATH);
    }

    @Override
    public BlindPayPayinResponse createEvmPayin(String payinQuoteId, String idempotencyKey) {
        requireId(payinQuoteId, "payinQuoteId");
        return post("Could not create the BlindPay payin",
                Map.of(BlindPayApi.Payin.PAYIN_QUOTE_ID, payinQuoteId), idempotencyKey,
                BlindPayPayinResponse.class, EVM_PAYINS_PATH);
    }

    @Override
    public BlindPayPayinResponse getPayin(String payinId) {
        requireId(payinId, "payinId");
        return get("Could not fetch the BlindPay payin",
                BlindPayPayinResponse.class, PAYIN_PATH, payinId);
    }

    private <T> T get(String operation, Class<T> responseType, String path, Object... pathVariables) {
        URI uri = uri(path, pathVariables);
        String authorization = bearerToken();

        try {
            String body = restClient.get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
            return deserialize(body, responseType, operation);
        } catch (RestClientResponseException ex) {
            throw providerError(operation, ex);
        } catch (RestClientException ex) {
            throw communicationError(operation, ex);
        }
    }

    private <T> T post(
            String operation,
            Object requestBody,
            String idempotencyKey,
            Class<T> responseType,
            String path,
            Object... pathVariables
    ) {
        validateIdempotencyKey(idempotencyKey);
        URI uri = uri(path, pathVariables);
        String authorization = bearerToken();
        String json = serialize(requestBody);

        try {
            String body = restClient.post()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .headers(headers -> {
                        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                            headers.set(BlindPayApi.Request.IDEMPOTENCY_KEY_HEADER, idempotencyKey);
                        }
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(json)
                    .retrieve()
                    .body(String.class);
            return deserialize(body, responseType, operation);
        } catch (RestClientResponseException ex) {
            throw providerError(operation, ex);
        } catch (RestClientException ex) {
            throw communicationError(operation, ex);
        }
    }

    /**
     * Variáveis de caminho são codificadas de forma estrita: um id com "/" ou "?"
     * não consegue alterar a rota chamada na BlindPay.
     */
    private URI uri(String path, Object... pathVariables) {
        Object[] variables = new Object[pathVariables.length + 1];
        variables[0] = instanceId();
        System.arraycopy(pathVariables, 0, variables, 1, pathVariables.length);

        return UriComponentsBuilder.fromUriString(baseUrl())
                .path(path)
                .encode()
                .buildAndExpand(variables)
                .toUri();
    }

    private String baseUrl() {
        return requireConfiguration(properties.getBaseUrl(), "BLINDPAY_BASE_URL");
    }

    private String instanceId() {
        return requireConfiguration(properties.getInstanceId(), "BLINDPAY_INSTANCE_ID");
    }

    private String bearerToken() {
        return "Bearer " + requireConfiguration(properties.getApiKey(), "BLINDPAY_API_KEY");
    }

    private String requireConfiguration(String value, String variableName) {
        if (value == null || value.isBlank()) {
            throw BlindPayIntegrationException.configuration(variableName + " is not configured", null);
        }
        return value;
    }

    private String serialize(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JacksonException ex) {
            throw BlindPayIntegrationException.configuration("Could not serialize the BlindPay request", ex);
        }
    }

    private <T> T deserialize(String body, Class<T> responseType, String operation) {
        if (body == null || body.isBlank()) {
            throw BlindPayIntegrationException.invalidResponse(operation + ": BlindPay returned an empty response");
        }
        try {
            return objectMapper.readValue(body, responseType);
        } catch (JacksonException ex) {
            throw BlindPayIntegrationException.invalidResponse(
                    operation + ": BlindPay returned an unreadable response");
        }
    }

    /**
     * Expõe somente o status HTTP, o {@code code} estável e a {@code description}
     * (texto que a BlindPay documenta como seguro para o usuário final). A exceção
     * original não vira causa porque a mensagem dela carrega o corpo completo.
     */
    private BlindPayIntegrationException providerError(String operation, RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        BlindPayErrorResponse error = parseError(exception.getResponseBodyAsString());
        String code = error == null ? null : blankToNull(error.code());
        String description = error == null ? null : blankToNull(error.description());
        String traceId = error == null ? null : blankToNull(error.traceId());

        log.warn("BlindPay request failed: operation='{}', status={}, code={}, traceId={}",
                operation, status, code, traceId);

        StringBuilder message = new StringBuilder(operation).append(": HTTP ").append(status);
        if (code != null) {
            message.append(" - ").append(code);
        }
        if (description != null) {
            message.append(": ").append(description);
        }
        return BlindPayIntegrationException.response(message.toString(), status, code, traceId, null);
    }

    private BlindPayErrorResponse parseError(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(body, BlindPayErrorResponse.class);
        } catch (JacksonException ignored) {
            // Respostas fora do envelope padrão ficam reduzidas ao status HTTP.
            return null;
        }
    }

    private BlindPayIntegrationException communicationError(String operation, RestClientException exception) {
        log.warn("BlindPay request could not be completed: operation='{}', error={}",
                operation, exception.getClass().getSimpleName());
        return BlindPayIntegrationException.communication(
                operation + ": BlindPay is unreachable", exception);
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey != null && idempotencyKey.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw new IllegalArgumentException("idempotencyKey must have at most 255 characters");
        }
    }

    private void requireRequest(Object request, String name) {
        if (request == null) {
            throw new IllegalArgumentException(name + " must be provided");
        }
    }

    private void requireId(String id, String name) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(name + " must be provided");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
