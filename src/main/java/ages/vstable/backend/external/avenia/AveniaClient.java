package ages.vstable.backend.external.avenia;

import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.external.avenia.dto.AveniaDocumentResponse;
import ages.vstable.backend.external.avenia.dto.AveniaDocumentStatusResponse;
import ages.vstable.backend.external.avenia.dto.AveniaDocumentUploadResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.security.PrivateKey;
import java.time.Instant;

@Component
public class AveniaClient {

    private static final String DOCUMENTS_URI = "/v2/documents/";
    private static final String LIVENESS_BODY = "{\"documentType\":\"SELFIE-FROM-LIVENESS\"}";

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
