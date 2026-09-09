package ages.vstable.backend.external.avenia;

import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.external.avenia.dto.AveniaDocumentResponse;
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
}
