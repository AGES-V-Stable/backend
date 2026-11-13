package ages.vstable.backend.external.avenia;

import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.exception.BlindPayIntegrationException;
import ages.vstable.backend.support.ProviderClientFixture;
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
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
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
    @ParameterizedTest(name="LC-OUT-01 resposta ilegivel {0} {1}")
    @CsvSource({"avenia,not-json", "blindpay,not-json", "avenia,array", "blindpay,array"})
    void malformedSuccessResponseBecomesControlledIntegrationFailure(String provider,String condition) throws Exception {
        RestClient.Builder builder=RestClient.builder();
        MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
        server.expect(anything()).andRespond(withSuccess(condition.equals("array")?"[]":"<html>synthetic</html>",MediaType.APPLICATION_JSON));
        Runnable call=client(provider,"https://provider.example.test",builder);
        assertThatThrownBy(call::run).isInstanceOfAny(AveniaIntegrationException.class,BlindPayIntegrationException.class);
        server.verify();
    }

    @ParameterizedTest(name="LC-OUT-02 redirect entre origens {0}")
    @ValueSource(strings={"avenia","blindpay"})
    @Timeout(15)
    void providerRedirectDoesNotReachAnotherOrigin(String provider) throws Exception {
        var reached=new java.util.concurrent.atomic.AtomicInteger();
        HttpServer sink=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        HttpServer origin=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        sink.createContext("/",exchange->{
            reached.incrementAndGet(); byte[] body="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        sink.start();
        origin.createContext("/",exchange->{
            exchange.getResponseHeaders().add("Location","http://127.0.0.1:"+sink.getAddress().getPort()+"/synthetic");
            exchange.sendResponseHeaders(302,-1); exchange.close();
        });
        origin.start();
        try {
            Runnable call=client(provider,"http://127.0.0.1:"+origin.getAddress().getPort(),RestClient.builder());
            try { call.run(); } catch(AveniaIntegrationException|BlindPayIntegrationException expected) { /* controlled refusal */ }
            assertThat(reached.get()).as("Nao seguir resposta do provedor para outra origem").isZero();
        } finally { origin.stop(0); sink.stop(0); }
    }

    @ParameterizedTest(name="LC-OUT-03 identificador nao muda autoridade {0}")
    @ValueSource(strings={"https://metadata.example.test/secret", "../other?token=synthetic", "//127.0.0.1/private"})
    void untrustedBlindPayIdentifierCannotChangeAuthority(String id) {
        RestClient.Builder builder=RestClient.builder();
        MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
        server.expect(request->{
            assertThat(request.getURI().getHost()).isEqualTo("provider.example.test");
            assertThat(request.getURI().getRawPath()).startsWith("/v1/instances/synthetic/customers/");
            assertThat(request.getURI().getRawQuery()).isNull();
        }).andRespond(withSuccess("{\"id\":\"synthetic\"}",MediaType.APPLICATION_JSON));
        var properties=new BlindPayProperties(); properties.setBaseUrl("https://provider.example.test");
        properties.setApiKey(UUID.randomUUID().toString()); properties.setInstanceId("synthetic");
        new BlindPayClient(properties,new ObjectMapper(),builder).getCustomer(id);
        server.verify();
    }
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
        return ProviderClientFixture.call(provider, origin, builder);
    }
}
