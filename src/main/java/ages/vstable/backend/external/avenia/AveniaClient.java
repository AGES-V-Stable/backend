package ages.vstable.backend.external.avenia;

import ages.vstable.backend.dto.compliance.KycSubmitRequest;
import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.external.avenia.dto.AveniaDocumentResponse;
import ages.vstable.backend.external.avenia.dto.AveniaDocumentStatusResponse;
import ages.vstable.backend.external.avenia.dto.AveniaDocumentUploadResponse;
import ages.vstable.backend.external.avenia.dto.AveniaKycRequest;
import ages.vstable.backend.external.avenia.dto.AveniaKycResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.security.PrivateKey;
import java.time.Instant;
import java.util.Map;

@Component
public class AveniaClient {

    private static final String DOCUMENTS_URI = "/v2/documents/";
    private static final String LIVENESS_BODY = "{\"documentType\":\"SELFIE-FROM-LIVENESS\"}";
    private static final String KYC_LEVEL_1_URI = "/v2/kyc/new-level-1/api";
    private static final String COUNTRY_OF_TAX_ID_BRAZIL = "BRA";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // O endpoint de KYC Level 1 exige ISO 3166-1 alpha-3 nos campos de país. O
    // restante do sistema guarda o nome por extenso (ver CompanyDataValidator),
    // então normalizamos "Brasil" para "BRA" somente ao montar o payload externo.
    private static final Map<String, String> COUNTRY_ISO_CODES = Map.of("brasil", "BRA");

    private final RestClient restClient;
    private final AveniaRequestSigner requestSigner;
    private final String apiKey;
    private final String privateKeyPem;
    private volatile PrivateKey privateKey;

    public AveniaClient(@Value("${app.avenia.base-url}") String baseUrl,
                         @Value("${app.avenia.api-key}") String apiKey,
                         @Value("${app.avenia.private-key}") String privateKeyPem,
                         AveniaRequestSigner requestSigner) {
        this.restClient = RestClient.create(baseUrl);
        this.apiKey = apiKey;
        this.privateKeyPem = privateKeyPem;
        this.requestSigner = requestSigner;
    }

    public AveniaDocumentResponse iniciarLiveness() {
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        String signature = requestSigner.sign(timestamp, "POST", DOCUMENTS_URI, LIVENESS_BODY, privateKey());

        try {
            return restClient.post()
                    .uri(DOCUMENTS_URI)
                    .header("X-API-Key", apiKey)
                    .header("X-API-Timestamp", timestamp)
                    .header("X-API-Signature", signature)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(LIVENESS_BODY)
                    .retrieve()
                    .body(AveniaDocumentResponse.class);
        } catch (RestClientException e) {
            throw new AveniaIntegrationException("Falha ao iniciar verificação de liveness na Avenia", e);
        }
    }

    public AveniaDocumentUploadResponse iniciarDocumento(String documentType, boolean isDoubleSided) {
        String body = "{\"documentType\":\"" + documentType + "\",\"isDoubleSided\":" + isDoubleSided + "}";
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        String signature = requestSigner.sign(timestamp, "POST", DOCUMENTS_URI, body, privateKey());

        try {
            return restClient.post()
                    .uri(DOCUMENTS_URI)
                    .header("X-API-Key", apiKey)
                    .header("X-API-Timestamp", timestamp)
                    .header("X-API-Signature", signature)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(AveniaDocumentUploadResponse.class);
        } catch (RestClientException e) {
            throw new AveniaIntegrationException("Falha ao iniciar upload de documento na Avenia", e);
        }
    }

    public AveniaDocumentStatusResponse consultarStatusDocumento(String documentId) {
        String uri = DOCUMENTS_URI + documentId;
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        String signature = requestSigner.sign(timestamp, "GET", uri, "", privateKey());

        try {
            return restClient.get()
                    .uri(uri)
                    .header("X-API-Key", apiKey)
                    .header("X-API-Timestamp", timestamp)
                    .header("X-API-Signature", signature)
                    .retrieve()
                    .body(AveniaDocumentStatusResponse.class);
        } catch (RestClientException e) {
            throw new AveniaIntegrationException("Falha ao consultar status da verificação de liveness na Avenia", e);
        }
    }

    public AveniaKycResponse finalizarKyc(KycSubmitRequest personalData, String documentId, String selfieId) {
        AveniaKycRequest aveniaRequest = new AveniaKycRequest();
        aveniaRequest.setFullName(personalData.getFullName());
        aveniaRequest.setDateOfBirth(personalData.getDateOfBirth());
        aveniaRequest.setCountryOfTaxId(COUNTRY_OF_TAX_ID_BRAZIL);
        aveniaRequest.setTaxIdNumber(personalData.getTaxIdNumber());
        aveniaRequest.setEmail(personalData.getEmail());
        aveniaRequest.setPhone(personalData.getPhone());
        aveniaRequest.setCountry(toIsoCountryCode(personalData.getCountry()));
        aveniaRequest.setState(personalData.getState());
        aveniaRequest.setCity(personalData.getCity());
        aveniaRequest.setZipCode(personalData.getZipCode());
        aveniaRequest.setStreetAddress(personalData.getStreetAddress());
        aveniaRequest.setUploadedDocumentId(documentId);
        aveniaRequest.setUploadedSelfieId(selfieId);

        String body;
        try {
            body = OBJECT_MAPPER.writeValueAsString(aveniaRequest);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao montar payload de KYC para a Avenia", e);
        }

        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        String signature = requestSigner.sign(timestamp, "POST", KYC_LEVEL_1_URI, body, privateKey());

        try {
            return restClient.post()
                    .uri(KYC_LEVEL_1_URI)
                    .header("X-API-Key", apiKey)
                    .header("X-API-Timestamp", timestamp)
                    .header("X-API-Signature", signature)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(AveniaKycResponse.class);
        } catch (RestClientResponseException e) {
            throw new AveniaIntegrationException(
                    "Falha ao finalizar KYC na Avenia: " + safeErrorDetail(e), e);
        } catch (RestClientException e) {
            throw new AveniaIntegrationException("Falha ao finalizar KYC na Avenia", e);
        }
    }

    /**
     * Expõe somente o código HTTP e a mensagem de validação retornada pela Avenia.
     * O corpo completo não é propagado porque pode conter dados de KYC.
     */
    private String safeErrorDetail(RestClientResponseException exception) {
        String status = "HTTP " + exception.getStatusCode().value();
        String body = exception.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return status;
        }

        try {
            JsonNode root = OBJECT_MAPPER.readTree(body);
            for (String field : new String[]{"message", "error", "detail", "errorMessage"}) {
                JsonNode value = root.get(field);
                if (value != null && value.isTextual() && !value.asText().isBlank()) {
                    return status + " - " + value.asText();
                }
            }
        } catch (JsonProcessingException ignored) {
            // Respostas não JSON ficam reduzidas ao status HTTP para não vazar conteúdo.
        }

        return status;
    }

    private String toIsoCountryCode(String country) {
        String isoCode = COUNTRY_ISO_CODES.get(country == null ? "" : country.trim().toLowerCase());
        if (isoCode == null) {
            throw new UnprocessableEntityException("País não suportado para verificação de KYC: " + country);
        }
        return isoCode;
    }

    private PrivateKey privateKey() {
        if (privateKey == null) {
            synchronized (this) {
                if (privateKey == null) {
                    privateKey = PemPrivateKeyLoader.load(privateKeyPem);
                }
            }
        }
        return privateKey;
    }
}
