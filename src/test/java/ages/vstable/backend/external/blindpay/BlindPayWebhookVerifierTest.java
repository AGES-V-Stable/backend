package ages.vstable.backend.external.blindpay;

import ages.vstable.backend.exception.BlindPayIntegrationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlindPayWebhookVerifierTest {

    // Vetor de teste público da documentação do Svix (provedor de webhooks da BlindPay).
    // Não é credencial real: serve para provar conformidade com o algoritmo de assinatura.
    private static final String SVIX_DOCS_SAMPLE_SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";
    private static final String MESSAGE_ID = "msg_p5jXN8AQM9LWM0D4loKWxJek";
    private static final long SIGNED_AT = 1614265330L;
    private static final String TIMESTAMP = String.valueOf(SIGNED_AT);
    private static final byte[] BODY = "{\"test\": 2432232314}".getBytes(StandardCharsets.UTF_8);
    private static final String SIGNATURE = "v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=";

    private BlindPayProperties properties;

    @BeforeEach
    void setUp() {
        properties = new BlindPayProperties();
        properties.setWebhookSecret(SVIX_DOCS_SAMPLE_SECRET);
    }

    @Test
    void acceptsSignatureFromSvixReferenceVector() {
        BlindPayWebhookVerifier verifier = verifierAt(SIGNED_AT);

        assertThat(verifier.isValid(MESSAGE_ID, TIMESTAMP, SIGNATURE, BODY)).isTrue();
    }

    @Test
    void acceptsWhenAnyOfSeveralSignaturesMatches() {
        BlindPayWebhookVerifier verifier = verifierAt(SIGNED_AT);
        String rotatedSecretHeader = "v1,c29tZS1vbGQtc2lnbmF0dXJl " + SIGNATURE;

        assertThat(verifier.isValid(MESSAGE_ID, TIMESTAMP, rotatedSecretHeader, BODY)).isTrue();
    }

    @Test
    void acceptsSecretConfiguredWithoutWhsecPrefix() {
        properties.setWebhookSecret(SVIX_DOCS_SAMPLE_SECRET.substring("whsec_".length()));

        assertThat(verifierAt(SIGNED_AT).isValid(MESSAGE_ID, TIMESTAMP, SIGNATURE, BODY)).isTrue();
    }

    @Test
    void rejectsTamperedBody() {
        byte[] tamperedBody = "{\"test\": 2432232315}".getBytes(StandardCharsets.UTF_8);

        assertThat(verifierAt(SIGNED_AT).isValid(MESSAGE_ID, TIMESTAMP, SIGNATURE, tamperedBody)).isFalse();
    }

    @Test
    void rejectsSignatureReplayedWithAnotherMessageId() {
        assertThat(verifierAt(SIGNED_AT).isValid("msg_another", TIMESTAMP, SIGNATURE, BODY)).isFalse();
    }

    @Test
    void rejectsSignatureProducedWithAnotherSecret() {
        properties.setWebhookSecret("whsec_" + Base64.getEncoder()
                .encodeToString("another-secret".getBytes(StandardCharsets.UTF_8)));

        assertThat(verifierAt(SIGNED_AT).isValid(MESSAGE_ID, TIMESTAMP, SIGNATURE, BODY)).isFalse();
    }

    @Test
    void rejectsUnsupportedSignatureVersion() {
        String v2Header = SIGNATURE.replace("v1,", "v2,");

        assertThat(verifierAt(SIGNED_AT).isValid(MESSAGE_ID, TIMESTAMP, v2Header, BODY)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(longs = {-300, 300})
    void acceptsTimestampAtToleranceBoundary(long clockSkewSeconds) {
        assertThat(verifierAt(SIGNED_AT + clockSkewSeconds).isValid(MESSAGE_ID, TIMESTAMP, SIGNATURE, BODY))
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(longs = {-301, 301, 86_400})
    void rejectsTimestampOutsideToleranceToPreventReplay(long clockSkewSeconds) {
        assertThat(verifierAt(SIGNED_AT + clockSkewSeconds).isValid(MESSAGE_ID, TIMESTAMP, SIGNATURE, BODY))
                .isFalse();
    }

    @Test
    void rejectsMissingOrMalformedHeaders() {
        BlindPayWebhookVerifier verifier = verifierAt(SIGNED_AT);

        assertThat(verifier.isValid(null, TIMESTAMP, SIGNATURE, BODY)).isFalse();
        assertThat(verifier.isValid(MESSAGE_ID, " ", SIGNATURE, BODY)).isFalse();
        assertThat(verifier.isValid(MESSAGE_ID, "not-a-number", SIGNATURE, BODY)).isFalse();
        assertThat(verifier.isValid(MESSAGE_ID, TIMESTAMP, "", BODY)).isFalse();
        assertThat(verifier.isValid(MESSAGE_ID, TIMESTAMP, SIGNATURE, null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "whsec_", "whsec_not base64!"})
    void failsWhenWebhookSecretIsMissingOrInvalid(String secret) {
        properties.setWebhookSecret(secret);
        BlindPayWebhookVerifier verifier = verifierAt(SIGNED_AT);

        assertThatThrownBy(() -> verifier.isValid(MESSAGE_ID, TIMESTAMP, SIGNATURE, BODY))
                .isInstanceOfSatisfying(BlindPayIntegrationException.class, ex -> {
                    assertThat(ex.getMessage()).startsWith("BLINDPAY_WEBHOOK_SECRET");
                    assertThat(ex.isRetryable()).isFalse();
                });
    }

    private BlindPayWebhookVerifier verifierAt(long epochSecond) {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC);
        return new BlindPayWebhookVerifier(properties, clock);
    }
}
