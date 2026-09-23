package ages.vstable.backend.external.avenia;

import ages.vstable.backend.dto.compliance.KycSubmitRequest;
import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.external.avenia.dto.AveniaDocumentResponse;
import ages.vstable.backend.external.avenia.dto.AveniaDocumentStatusResponse;
import ages.vstable.backend.external.avenia.dto.AveniaDocumentUploadResponse;
import ages.vstable.backend.external.avenia.dto.AveniaKycResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AveniaClientTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private KeyPair gerarKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private String pemFor(KeyPair keyPair) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
    }

    // Shape real da resposta da Avenia sandbox, confirmado em teste manual em 2026-09-09
    @Test
    void iniciarLiveness_respostaComSucesso_retornaCamposEEnviaHeadersDeAssinatura() throws Exception {
        KeyPair keyPair = gerarKeyPair();
        AtomicReference<String> headerApiKey = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();

        server.createContext("/v2/documents/", exchange -> {
            headerApiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            String responseBody = "{\"id\":\"liveness-123\",\"sessionId\":\"session-456\","
                    + "\"livenessUrl\":\"https://app.sandbox.avenia.io/liveness/session-456?jwt=abc\","
                    + "\"validateLivenessToken\":\"token-789\"}";
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        AveniaClient client = new AveniaClient(baseUrl, "minha-api-key", pemFor(keyPair), new AveniaRequestSigner());

        AveniaDocumentResponse response = client.iniciarLiveness();

        assertThat(response.getId()).isEqualTo("liveness-123");
        assertThat(response.getSessionId()).isEqualTo("session-456");
        assertThat(response.getLivenessUrl()).isEqualTo("https://app.sandbox.avenia.io/liveness/session-456?jwt=abc");
        assertThat(response.getValidateLivenessToken()).isEqualTo("token-789");
        assertThat(headerApiKey.get()).isEqualTo("minha-api-key");
        assertThat(requestBody.get()).isEqualTo("{\"documentType\":\"SELFIE-FROM-LIVENESS\"}");
    }

    @Test
    void iniciarLiveness_servidorRetornaErro_lancaAveniaIntegrationException() throws Exception {
        KeyPair keyPair = gerarKeyPair();

        server.createContext("/v2/documents/", exchange -> {
            byte[] bytes = "erro interno".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        AveniaClient client = new AveniaClient(baseUrl, "minha-api-key", pemFor(keyPair), new AveniaRequestSigner());

        assertThatThrownBy(client::iniciarLiveness)
                .isInstanceOf(AveniaIntegrationException.class);
    }

    @Test
    void iniciarLiveness_semChavePrivadaConfigurada_lancaIllegalStateException() {
        AveniaClient client = new AveniaClient(baseUrl, "minha-api-key", "", new AveniaRequestSigner());

        assertThatThrownBy(client::iniciarLiveness)
                .isInstanceOf(IllegalStateException.class);
    }

    // Shape real confirmado em 2026-09-21 contra o sandbox: POST /v2/documents/
    // com documentType ID devolve "uploadURLFront"/"uploadURLBack" (URL maiúsculo).
    @Test
    void iniciarDocumento_respostaComSucesso_retornaCamposEEnviaBodyComDocumentType() throws Exception {
        KeyPair keyPair = gerarKeyPair();
        AtomicReference<String> requestBody = new AtomicReference<>();

        server.createContext("/v2/documents/", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            String responseBody = "{\"id\":\"doc-123\",\"uploadURLFront\":\"https://s3/front\","
                    + "\"uploadURLBack\":\"https://s3/back\"}";
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        AveniaClient client = new AveniaClient(baseUrl, "minha-api-key", pemFor(keyPair), new AveniaRequestSigner());

        AveniaDocumentUploadResponse response = client.iniciarDocumento("ID", true);

        assertThat(response.getId()).isEqualTo("doc-123");
        assertThat(response.getUploadUrlFront()).isEqualTo("https://s3/front");
        assertThat(response.getUploadUrlBack()).isEqualTo("https://s3/back");
        assertThat(requestBody.get()).isEqualTo("{\"documentType\":\"ID\",\"isDoubleSided\":true}");
    }

    @Test
    void iniciarDocumento_servidorRetornaErro_lancaAveniaIntegrationException() throws Exception {
        KeyPair keyPair = gerarKeyPair();

        server.createContext("/v2/documents/", exchange -> {
            byte[] bytes = "erro interno".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        AveniaClient client = new AveniaClient(baseUrl, "minha-api-key", pemFor(keyPair), new AveniaRequestSigner());

        assertThatThrownBy(() -> client.iniciarDocumento("PASSPORT", false))
                .isInstanceOf(AveniaIntegrationException.class);
    }

    // Shape real confirmado em 2026-09-16 contra o sandbox: GET /v2/documents/{id}
    @Test
    void consultarStatusDocumento_respostaComSucesso_retornaDocumentoComReadyEStatus() throws Exception {
        KeyPair keyPair = gerarKeyPair();
        AtomicReference<String> requestMethod = new AtomicReference<>();
        AtomicReference<String> requestPath = new AtomicReference<>();

        server.createContext("/v2/documents/doc-123", exchange -> {
            requestMethod.set(exchange.getRequestMethod());
            requestPath.set(exchange.getRequestURI().getPath());

            String responseBody = "{\"document\":{\"id\":\"doc-123\",\"documentType\":\"SELFIE-FROM-LIVENESS\","
                    + "\"uploadStatusFront\":\"WAITING-UPLOAD\",\"uploadErrorFront\":\"\","
                    + "\"uploadStatusBack\":\"\",\"uploadErrorBack\":\"\",\"ready\":false}}";
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        AveniaClient client = new AveniaClient(baseUrl, "minha-api-key", pemFor(keyPair), new AveniaRequestSigner());

        AveniaDocumentStatusResponse response = client.consultarStatusDocumento("doc-123");

        assertThat(response.getDocument().getId()).isEqualTo("doc-123");
        assertThat(response.getDocument().isReady()).isFalse();
        assertThat(response.getDocument().getUploadStatusFront()).isEqualTo("WAITING-UPLOAD");
        assertThat(requestMethod.get()).isEqualTo("GET");
        assertThat(requestPath.get()).isEqualTo("/v2/documents/doc-123");
    }

    @Test
    void consultarStatusDocumento_servidorRetornaErro_lancaAveniaIntegrationException() throws Exception {
        KeyPair keyPair = gerarKeyPair();

        server.createContext("/v2/documents/doc-123", exchange -> {
            byte[] bytes = "erro interno".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        AveniaClient client = new AveniaClient(baseUrl, "minha-api-key", pemFor(keyPair), new AveniaRequestSigner());

        assertThatThrownBy(() -> client.consultarStatusDocumento("doc-123"))
                .isInstanceOf(AveniaIntegrationException.class);
    }

    private KycSubmitRequest kycRequest() {
        KycSubmitRequest request = new KycSubmitRequest();
        request.setFullName("Maria da Silva");
        request.setDateOfBirth("1990-05-20");
        request.setTaxIdNumber("52998224725");
        request.setEmail("maria@empresa.com");
        request.setPhone("11987654321");
        request.setCountry("Brasil");
        request.setState("SP");
        request.setCity("São Paulo");
        request.setZipCode("90000000");
        request.setStreetAddress("Rua Teste, 100");
        return request;
    }

    // Shape NÃO confirmado contra o sandbox real
    @Test
    void finalizarKyc_respostaComSucesso_retornaIdEEnviaBodyComDadosPessoaisEIds() throws Exception {
        KeyPair keyPair = gerarKeyPair();
        AtomicReference<String> requestBody = new AtomicReference<>();

        server.createContext("/v2/kyc/new-level-1/api", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            String responseBody = "{\"id\":\"kyc-process-123\"}";
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        AveniaClient client = new AveniaClient(baseUrl, "minha-api-key", pemFor(keyPair), new AveniaRequestSigner());

        AveniaKycResponse response = client.finalizarKyc(kycRequest(), "doc-123", "liveness-123");

        assertThat(response.getId()).isEqualTo("kyc-process-123");
        assertThat(requestBody.get()).contains("\"fullName\":\"Maria da Silva\"");
        assertThat(requestBody.get()).contains("\"countryOfTaxId\":\"BRA\"");
        assertThat(requestBody.get()).contains("\"taxIdNumber\":\"52998224725\"");
        assertThat(requestBody.get()).contains("\"uploadedDocumentId\":\"doc-123\"");
        assertThat(requestBody.get()).contains("\"uploadedSelfieId\":\"liveness-123\"");
        // O KYC Level 1 exige código ISO 3166-1 alpha-3 para países.
        assertThat(requestBody.get()).contains("\"country\":\"BRA\"");
    }

    // Confirmado contra o sandbox real (400 "InvalidFieldError: country is invalid" ao
    // enviar "Brasil" por extenso), daí a normalização para código ISO antes do envio.
    @Test
    void finalizarKyc_paisNaoSuportado_lancaUnprocessableEntityExceptionSemChamarAvenia() throws Exception {
        KeyPair keyPair = gerarKeyPair();
        AveniaClient client = new AveniaClient(baseUrl, "minha-api-key", pemFor(keyPair), new AveniaRequestSigner());

        KycSubmitRequest request = kycRequest();
        request.setCountry("Argentina");

        assertThatThrownBy(() -> client.finalizarKyc(request, "doc-123", "liveness-123"))
                .isInstanceOf(ages.vstable.backend.exception.UnprocessableEntityException.class);
    }

    @Test
    void finalizarKyc_servidorRetornaErro_lancaAveniaIntegrationException() throws Exception {
        KeyPair keyPair = gerarKeyPair();

        server.createContext("/v2/kyc/new-level-1/api", exchange -> {
            byte[] bytes = "erro interno".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        AveniaClient client = new AveniaClient(baseUrl, "minha-api-key", pemFor(keyPair), new AveniaRequestSigner());

        assertThatThrownBy(() -> client.finalizarKyc(kycRequest(), "doc-123", "liveness-123"))
                .isInstanceOf(AveniaIntegrationException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    void finalizarKyc_aveniaRetornaMensagemDeValidacao_propagaSomenteStatusEMensagem() throws Exception {
        KeyPair keyPair = gerarKeyPair();

        server.createContext("/v2/kyc/new-level-1/api", exchange -> {
            byte[] bytes = "{\"message\":\"country is invalid\",\"sensitiveData\":\"nao-propagar\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        AveniaClient client = new AveniaClient(baseUrl, "minha-api-key", pemFor(keyPair), new AveniaRequestSigner());

        assertThatThrownBy(() -> client.finalizarKyc(kycRequest(), "doc-123", "liveness-123"))
                .isInstanceOf(AveniaIntegrationException.class)
                .hasMessage("Falha ao finalizar KYC na Avenia: HTTP 400 - country is invalid")
                .hasMessageNotContaining("sensitiveData")
                .hasMessageNotContaining("nao-propagar");
    }
}
