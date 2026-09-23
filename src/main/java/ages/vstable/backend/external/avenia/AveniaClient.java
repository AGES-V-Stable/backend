package ages.vstable.backend.external.avenia;

import ages.vstable.backend.dto.compliance.KycSubmitRequest;
import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.external.avenia.dto.AveniaDocumentResponse;
import ages.vstable.backend.external.avenia.dto.AveniaDocumentStatusResponse;
import ages.vstable.backend.external.avenia.dto.AveniaDocumentUploadResponse;
import ages.vstable.backend.external.avenia.dto.AveniaKycAttempt;
import ages.vstable.backend.external.avenia.dto.AveniaKycAttemptResponse;
import ages.vstable.backend.external.avenia.dto.AveniaKycAttemptsResponse;
import ages.vstable.backend.external.avenia.dto.AveniaKycRequest;
import ages.vstable.backend.external.avenia.dto.AveniaKycResponse;
import ages.vstable.backend.external.avenia.dto.AveniaSubAccountResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.Map;

@Component
public class AveniaClient {

    private static final String DOCUMENTS_URI = "/v2/documents/";
    private static final String LIVENESS_BODY = "{\"documentType\":\"SELFIE-FROM-LIVENESS\"}";
    private static final String KYC_LEVEL_1_URI = "/v2/kyc/new-level-1/api";
    private static final String SUB_ACCOUNTS_URI = "/v2/account/sub-accounts";
    private static final String KYC_ATTEMPTS_URI = "/v2/kyc/attempts/";
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

    public AveniaDocumentResponse iniciarLiveness(String subAccountId) {
        String uri = withSubAccount(DOCUMENTS_URI, subAccountId);
        return post(uri, LIVENESS_BODY, AveniaDocumentResponse.class,
                "Falha ao iniciar verificação de liveness na Avenia");
    }

    public AveniaDocumentUploadResponse iniciarDocumento(String documentType, boolean isDoubleSided, String subAccountId) {
        String body = "{\"documentType\":\"" + documentType + "\",\"isDoubleSided\":" + isDoubleSided + "}";
        String uri = withSubAccount(DOCUMENTS_URI, subAccountId);
        return post(uri, body, AveniaDocumentUploadResponse.class,
                "Falha ao iniciar upload de documento na Avenia");
    }

    public AveniaDocumentStatusResponse consultarStatusDocumento(String documentId, String subAccountId) {
        String uri = withSubAccount(DOCUMENTS_URI + documentId, subAccountId);
        return get(uri, AveniaDocumentStatusResponse.class,
                "Falha ao consultar status da verificação de liveness na Avenia");
    }

    /**
     * Cria uma subconta (identidade individual) na Avenia. Cada verificação de
     * KYC local precisa da sua própria subconta — usar sempre a conta principal
     * (vinculada à API key) faz a Avenia rejeitar submissões repetidas com
     * "user already approved in level 1" assim que a conta principal for
     * aprovada uma vez. Confirmado contra o sandbox real em 2026-09-23.
     */
    public AveniaSubAccountResponse criarSubconta(String name) {
        String body;
        try {
            body = OBJECT_MAPPER.writeValueAsString(Map.of("accountType", "INDIVIDUAL", "name", name));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao montar payload de criação de subconta", e);
        }
        return post(SUB_ACCOUNTS_URI, body, AveniaSubAccountResponse.class,
                "Falha ao criar subconta na Avenia");
    }

    /**
     * Lista as tentativas de KYC Level 1 já existentes para a subconta, mais
     * recentes primeiro. Usada para checar, antes de submeter um novo KYC, se
     * já existe uma tentativa em andamento ou concluída.
     */
    public AveniaKycAttemptsResponse listarTentativasKyc(String subAccountId) {
        String uri = KYC_ATTEMPTS_URI + "?levelName=level-1&subAccountId=" + encode(subAccountId);
        return get(uri, AveniaKycAttemptsResponse.class,
                "Falha ao consultar tentativas de KYC na Avenia");
    }

    public AveniaKycAttempt consultarTentativa(String attemptId, String subAccountId) {
        String uri = withSubAccount(KYC_ATTEMPTS_URI + attemptId, subAccountId);
        AveniaKycAttemptResponse response = get(uri, AveniaKycAttemptResponse.class,
                "Falha ao consultar tentativa de KYC na Avenia");
        return response.getAttempt();
    }

    public AveniaKycResponse finalizarKyc(KycSubmitRequest personalData, String documentId, String selfieId, String subAccountId) {
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

        String uri = withSubAccount(KYC_LEVEL_1_URI, subAccountId);
        return post(uri, body, AveniaKycResponse.class, "Falha ao finalizar KYC na Avenia");
    }

    private <T> T post(String uri, String body, Class<T> responseType, String errorMessage) {
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        String signature = requestSigner.sign(timestamp, "POST", uri, body, privateKey());

        try {
            return restClient.post()
                    .uri(uri)
                    .header("X-API-Key", apiKey)
                    .header("X-API-Timestamp", timestamp)
                    .header("X-API-Signature", signature)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(responseType);
        } catch (RestClientResponseException e) {
            throw new AveniaIntegrationException(errorMessage + ": " + safeErrorDetail(e), e);
        } catch (RestClientException e) {
            throw new AveniaIntegrationException(errorMessage, e);
        }
    }

    private <T> T get(String uri, Class<T> responseType, String errorMessage) {
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        String signature = requestSigner.sign(timestamp, "GET", uri, "", privateKey());

        try {
            return restClient.get()
                    .uri(uri)
                    .header("X-API-Key", apiKey)
                    .header("X-API-Timestamp", timestamp)
                    .header("X-API-Signature", signature)
                    .retrieve()
                    .body(responseType);
        } catch (RestClientResponseException e) {
            throw new AveniaIntegrationException(errorMessage + ": " + safeErrorDetail(e), e);
        } catch (RestClientException e) {
            throw new AveniaIntegrationException(errorMessage, e);
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

    private String withSubAccount(String uri, String subAccountId) {
        if (subAccountId == null || subAccountId.isBlank()) {
            return uri;
        }
        return uri + "?subAccountId=" + encode(subAccountId);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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
