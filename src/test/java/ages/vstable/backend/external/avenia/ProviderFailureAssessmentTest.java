package ages.vstable.backend.external.avenia;

import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.exception.BlindPayIntegrationException;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteRequest;
import ages.vstable.backend.external.blindpay.BlindPayClient;
import ages.vstable.backend.external.blindpay.BlindPayProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** Nenhum provedor real: falhas de transporte simuladas e servidor apenas em loopback. */
@Tag("security-assessment")
class ProviderFailureAssessmentTest {
    @ParameterizedTest(name="TC-INT-10 transporte {0} {1}")
    @CsvSource({"avenia,timeout", "blindpay,timeout", "avenia,tls", "blindpay,tls"})
    void transportFailureIsRetryableAndDoesNotTriggerAutomaticRetry(String provider, String fault) throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        IOException failure = fault.equals("timeout") ? new SocketTimeoutException("synthetic") : new SSLHandshakeException("synthetic");
        server.expect(anything()).andRespond(withException(failure));
        assertRetryable(provider, client(provider, "https://provider.example.test", builder));
        server.verify();
    }

    @ParameterizedTest(name="TC-INT-11 erro HTTP {0} {1}")
    @CsvSource({"avenia,429", "avenia,500", "avenia,503", "blindpay,429", "blindpay,500", "blindpay,503"})
    void transientHttpErrorIsRetryableWithoutUnboundedRetries(String provider, int code) throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(anything()).andRespond(withStatus(HttpStatusCode.valueOf(code)).contentType(MediaType.APPLICATION_JSON).body("{}"));
        assertRetryable(provider, client(provider, "https://provider.example.test", builder));
        server.verify();
    }

    @ParameterizedTest(name="TC-INT-12 timeout real de leitura em loopback {0}")
    @ValueSource(strings={"avenia", "blindpay"})
    @Timeout(15)
    void readDeadlineIsEnforcedAgainstLocalStalledServer(String provider) throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var executor = Executors.newSingleThreadExecutor()) {
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                received.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            server.start();
            try {
                SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                factory.setConnectTimeout(Duration.ofSeconds(1));
                factory.setReadTimeout(Duration.ofMillis(200));
                Runnable call = client(provider, "http://127.0.0.1:" + server.getAddress().getPort(), RestClient.builder().requestFactory(factory));
                assertRetryable(provider, call);
                assertThat(received.getCount()).isZero();
            } finally {
                release.countDown();
                server.stop(0);
            }
        }
    }

    private void assertRetryable(String provider, Runnable call) {
        if (provider.equals("avenia")) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(AveniaIntegrationException.class,
                    error -> assertThat(error.isRetryable()).isTrue());
        } else {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(BlindPayIntegrationException.class,
                    error -> assertThat(error.isRetryable()).isTrue());
        }
    }

    private Runnable client(String provider, String origin, RestClient.Builder builder) throws Exception {
        if (provider.equals("blindpay")) {
            BlindPayProperties properties = new BlindPayProperties();
            properties.setBaseUrl(origin); properties.setApiKey(UUID.randomUUID().toString()); properties.setInstanceId("synthetic");
            BlindPayClient client = new BlindPayClient(properties, new ObjectMapper(), builder);
            return () -> client.getCustomer("synthetic");
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        AveniaProperties properties = new AveniaProperties();
        properties.setBaseUrl(origin); properties.setApiKey(UUID.randomUUID().toString());
        properties.setPrivateKey("-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----");
        AveniaClient client = new AveniaClient(properties, new AveniaRequestSigner(), new ObjectMapper(), builder);
        AveniaQuoteRequest request = new AveniaQuoteRequest("BRL", "PIX", "USDC", "POLYGON", new BigDecimal("100"), null,
                false, false, null, null, null, null, null, null, null, "synthetic");
        return () -> client.createQuote(request);
    }
}
