package ages.vstable.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    }

    @Test
    void buildOtpAuthUri_deveLancarExcecaoParaParametrosInvalidos() {
        assertThatThrownBy(() -> totpService.buildOtpAuthUri("", "JBSWY3DPEHPK3PXP"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> totpService.buildOtpAuthUri("user@test.com", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}