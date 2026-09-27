package ages.vstable.backend.external.avenia;

import ages.vstable.backend.dto.transfer.AmountType;
import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteResult;
import ages.vstable.backend.external.avenia.dto.AveniaTicketResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
public class AveniaClient {

    private static final int TOKEN_EXPIRY_SECONDS = 3600;

    private final AveniaProperties properties;
    private final RestClient restClient;

    private volatile String cachedToken;
    private volatile Instant tokenExpiry;

    public AveniaClient(AveniaProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.create(properties.getBaseUrl());
    }

    public AveniaQuoteResult getQuote(String inputCurrency, String outputCurrency,
                                      BigDecimal amount, AmountType amountType) {
        try {
            boolean isSource = amountType == AmountType.SOURCE;
            return restClient.get()
                    .uri(uri -> uri.path("/v2/account/quote/fixed-rate")
                            .queryParam(AveniaApi.Quote.INPUT_CURRENCY, inputCurrency)
                            .queryParam(AveniaApi.Quote.OUTPUT_CURRENCY, outputCurrency)
                            .queryParam(AveniaApi.Quote.INPUT_PAYMENT_METHOD, AveniaApi.Ticket.QUOTE_TOKEN)
                            .queryParam(AveniaApi.Quote.OUTPUT_PAYMENT_METHOD, AveniaApi.Ticket.QUOTE_TOKEN)
                            .queryParamIfPresent(AveniaApi.Quote.INPUT_AMOUNT,
                                    Optional.ofNullable(isSource ? amount : null))
                            .queryParamIfPresent(AveniaApi.Quote.OUTPUT_AMOUNT,
                                    Optional.ofNullable(!isSource ? amount : null))
                            .build())
                    .header("Authorization", "Bearer " + getAccessToken())
                    .retrieve()
                    .body(AveniaQuoteResult.class);
        } catch (RestClientResponseException ex) {
            throw new AveniaIntegrationException(
                    "Avenia quote failed (" + ex.getStatusCode() + "): " + ex.getResponseBodyAsString(), ex);
        } catch (RestClientException ex) {
            throw new AveniaIntegrationException("Failed to reach Avenia for quote: " + ex.getMessage(), ex);
        }
    }

    public AveniaTicketResult createTicket(String quoteToken, UUID externalId,
                                           UUID beneficiaryId, String destinationCurrency) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put(AveniaApi.Ticket.QUOTE_TOKEN, quoteToken);
            body.put(AveniaApi.Ticket.EXTERNAL_ID, externalId.toString());
            body.put(resolveBeneficiaryField(destinationCurrency), beneficiaryId.toString());

            return restClient.post()
                    .uri("/v2/account/tickets/")
                    .header("Authorization", "Bearer " + getAccessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(AveniaTicketResult.class);
        } catch (RestClientResponseException ex) {
            throw new AveniaIntegrationException(
                    "Avenia ticket creation failed (" + ex.getStatusCode() + "): " + ex.getResponseBodyAsString(), ex);
        } catch (RestClientException ex) {
            throw new AveniaIntegrationException("Failed to reach Avenia for ticket creation: " + ex.getMessage(), ex);
        }
    }

    private synchronized String getAccessToken() {
        if (cachedToken != null && tokenExpiry != null && Instant.now().isBefore(tokenExpiry)) {
            return cachedToken;
        }
        try {
            Map<?, ?> loginResponse = restClient.post()
                    .uri("/v2/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(AveniaApi.Auth.EMAIL, properties.getEmail(),
                            AveniaApi.Auth.PASSWORD, properties.getPassword()))
                    .retrieve()
                    .body(Map.class);

            String emailToken = (String) loginResponse.get(AveniaApi.Auth.EMAIL_TOKEN);

            Map<?, ?> validateResponse = restClient.post()
                    .uri("/v2/auth/validate-login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(AveniaApi.Auth.EMAIL, properties.getEmail(),
                            AveniaApi.Auth.EMAIL_TOKEN, emailToken))
                    .retrieve()
                    .body(Map.class);

            cachedToken = (String) validateResponse.get(AveniaApi.Auth.ACCESS_TOKEN);
            tokenExpiry = Instant.now().plusSeconds(TOKEN_EXPIRY_SECONDS);
            return cachedToken;
        } catch (RestClientException ex) {
            throw new AveniaIntegrationException("Avenia authentication failed: " + ex.getMessage(), ex);
        }
    }

    private String resolveBeneficiaryField(String destinationCurrency) {
        return switch (destinationCurrency.toUpperCase()) {
            case "USD" -> AveniaApi.Beneficiary.BENEFICIARY_USD_BANK_ACCOUNT_ID;
            case "EUR" -> AveniaApi.Beneficiary.BENEFICIARY_EUR_BANK_ACCOUNT_ID;
            case "BRL" -> AveniaApi.Beneficiary.BENEFICIARY_BRL_BANK_ACCOUNT_ID;
            case "COP" -> AveniaApi.Beneficiary.BENEFICIARY_COP_BANK_ACCOUNT_ID;
            case "ARS" -> AveniaApi.Beneficiary.BENEFICIARY_ARS_BANK_ACCOUNT_ID;
            case "MXN" -> AveniaApi.Beneficiary.BENEFICIARY_MXN_BANK_ACCOUNT_ID;
            default -> throw new IllegalArgumentException("Unsupported destination currency: " + destinationCurrency);
        };
    }
}
