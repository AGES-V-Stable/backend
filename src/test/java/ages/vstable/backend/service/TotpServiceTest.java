package ages.vstable.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TotpServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-10-16T12:00:00Z");
    private TotpService totpService;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        totpService = new TotpService(fixedClock);
    }

    @Test
    void generateSecret_deveGerarSegredoBase32Com32CaracteresDe160Bits() {
        String secret1 = totpService.generateSecret();
        String secret2 = totpService.generateSecret();

        assertThat(secret1)
                .isNotNull()
                .hasSize(32)
                .matches("^[A-Z2-7]+$")
                .isNotEqualTo(secret2);
    }

    @Test
    void buildOtpAuthUri_deveMontarUriCompativelComGoogleEMicrosoftAuthenticator() {
        String secret = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";
        String uri = totpService.buildOtpAuthUri("usuario@empresa.com", secret);

        assertThat(uri).isEqualTo(
                "otpauth://totp/V-Stable:usuario%40empresa.com?secret=" + secret
                        + "&issuer=V-Stable&algorithm=SHA1&digits=6&period=30"
        );
    }

    @Test
    void verifyCode_deveAceitarCodigoCorretoDaJanelaAtual() {
        String secret = totpService.generateSecret();
        long currentWindow = FIXED_INSTANT.getEpochSecond() / 30L;
        String validCode = totpService.generateCodeForWindow(TotpService.decodeBase32(secret), currentWindow);

        assertThat(totpService.verifyCode(secret, validCode)).isTrue();
    }

    @Test
    void verifyCode_deveAceitarCodigoComToleranciaDeMaisOuMenosUmaJanela() {
        String secret = totpService.generateSecret();
        long currentWindow = FIXED_INSTANT.getEpochSecond() / 30L;
        byte[] keyBytes = TotpService.decodeBase32(secret);

        String previousWindowCode = totpService.generateCodeForWindow(keyBytes, currentWindow - 1);
        String nextWindowCode = totpService.generateCodeForWindow(keyBytes, currentWindow + 1);

        assertThat(totpService.verifyCode(secret, previousWindowCode)).isTrue();
        assertThat(totpService.verifyCode(secret, nextWindowCode)).isTrue();
    }

    @Test
    void verifyCode_deveRejeitarCodigoExpiradoForaDaTolerancia() {
        String secret = totpService.generateSecret();
        long currentWindow = FIXED_INSTANT.getEpochSecond() / 30L;
        byte[] keyBytes = TotpService.decodeBase32(secret);

        // 2 janelas atrás (60 segundos atrás) e 2 janelas à frente
        String expiredCode = totpService.generateCodeForWindow(keyBytes, currentWindow - 2);
        String futureCode = totpService.generateCodeForWindow(keyBytes, currentWindow + 2);

        assertThat(totpService.verifyCode(secret, expiredCode)).isFalse();
        assertThat(totpService.verifyCode(secret, futureCode)).isFalse();
    }

    @Test
    void verifyCode_deveRejeitarCodigoIncorretoOuMalformado() {
        String secret = totpService.generateSecret();

        assertThat(totpService.verifyCode(secret, "000000")).isFalse();
        assertThat(totpService.verifyCode(secret, "12345")).isFalse();
        assertThat(totpService.verifyCode(secret, "abcdef")).isFalse();
        assertThat(totpService.verifyCode(secret, null)).isFalse();
        assertThat(totpService.verifyCode(null, "123456")).isFalse();
        assertThat(totpService.verifyCode("   ", "123456")).isFalse();
        assertThat(totpService.verifyCode("INVALID!@#", "123456")).isFalse();
        assertThat(totpService.verifyCode("===", "123456")).isFalse();
    }

    @Test
    void generateCodeForWindow_matchesRfc4226DeterministicVectors() {
        byte[] rfcKey = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        String rfcBase32Secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

        assertThat(totpService.generateCodeForWindow(rfcKey, 0L)).isEqualTo("755224");
        assertThat(totpService.generateCodeForWindow(rfcKey, 1L)).isEqualTo("287082");
        assertThat(totpService.generateCodeForWindow(rfcKey, 2L)).isEqualTo("359152");
        assertThat(totpService.generateCodeForWindow(rfcKey, 3L)).isEqualTo("969429");

        TotpService rfcTotpService = new TotpService(Clock.fixed(Instant.ofEpochSecond(59L), ZoneOffset.UTC));
        assertThat(rfcTotpService.verifyCode(rfcBase32Secret, "755224")).isTrue();
        assertThat(rfcTotpService.verifyCode(rfcBase32Secret, "287082")).isTrue();
        assertThat(rfcTotpService.verifyCode(rfcBase32Secret, "359152")).isTrue();
        assertThat(rfcTotpService.verifyCode(rfcBase32Secret, "969429")).isFalse();
    }

    @Test
    void encodeAndDecodeBase32_matchesRfc4648VectorsAndHandlesTrailingPartialBits() {
        assertThat(TotpService.encodeBase32("".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(TotpService.encodeBase32("f".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MY");
        assertThat(TotpService.encodeBase32("fo".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXQ");
        assertThat(TotpService.encodeBase32("foo".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6");
        assertThat(TotpService.encodeBase32("foob".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YQ");
        assertThat(TotpService.encodeBase32("fooba".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTB");
        assertThat(TotpService.encodeBase32("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
        assertThat(TotpService.encodeBase32(new byte[]{(byte) 0xFF})).isEqualTo("74");
        assertThat(TotpService.encodeBase32("12345678901234567890".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");

        assertThat(TotpService.decodeBase32("MY"))
                .isEqualTo("f".getBytes(StandardCharsets.US_ASCII));
        assertThat(TotpService.decodeBase32("MZXQ=="))
                .isEqualTo("fo".getBytes(StandardCharsets.US_ASCII));
        assertThat(TotpService.decodeBase32("MZXW6==="))
                .isEqualTo("foo".getBytes(StandardCharsets.US_ASCII));
        assertThat(TotpService.decodeBase32("MZXW6YQ="))
                .isEqualTo("foob".getBytes(StandardCharsets.US_ASCII));
        assertThat(TotpService.decodeBase32("MZXW6YTB"))
                .isEqualTo("fooba".getBytes(StandardCharsets.US_ASCII));
        assertThat(TotpService.decodeBase32("mzxw6ytboi"))
                .isEqualTo("foobar".getBytes(StandardCharsets.US_ASCII));
        assertThat(TotpService.decodeBase32("AAAA"))
                .isEqualTo(new byte[]{0x00, 0x00});
        assertThat(TotpService.decodeBase32("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"))
                .isEqualTo("12345678901234567890".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void constantTimeEquals_returnsFalseWhenLengthsDiffer() throws Exception {
        Method method = TotpService.class.getDeclaredMethod("constantTimeEquals", String.class, String.class);
        method.setAccessible(true);

        assertThat((Boolean) method.invoke(null, "123456", "12345")).isFalse();
        assertThat((Boolean) method.invoke(null, "123456", "123456")).isTrue();
    }

    @Test
    void buildOtpAuthUri_deveLancarExcecaoParaParametrosInvalidos() {
        assertThatThrownBy(() -> totpService.buildOtpAuthUri(null, "JBSWY3DPEHPK3PXP"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> totpService.buildOtpAuthUri("", "JBSWY3DPEHPK3PXP"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> totpService.buildOtpAuthUri("user@test.com", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> totpService.buildOtpAuthUri("user@test.com", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}