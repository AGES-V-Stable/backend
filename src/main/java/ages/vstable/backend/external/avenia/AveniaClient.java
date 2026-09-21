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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.security.PrivateKey;
import java.time.Instant;
import java.util.Map;

@Component
public class AveniaClient {

    private static final String DOCUMENTS_URI = "/v2/documents/";
    private static final String LIVENESS_BODY = "{\"documentType\":\"SELFIE-FROM-LIVENESS\"}";
    private static final String KYC_LEVEL_1_URI = "/v2/kyc/new-level-1/api";
    private static final String COUNTRY_OF_TAX_ID_BRAZIL = "BR";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // A Avenia exige código ISO 3166-1 alpha-2 no campo "country" (confirmado contra o
    // sandbox: "Brasil" é rejeitado com "InvalidFieldError: country is invalid"), mas o
    // resto do sistema guarda/usa o nome do país por extenso (ver CompanyDataValidator).
    private static final Map<String, String> COUNTRY_ISO_CODES = Map.of("brasil", "BR");

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
        } catch (RestClientException e) {
            throw new AveniaIntegrationException("Falha ao finalizar KYC na Avenia", e);
        }
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
