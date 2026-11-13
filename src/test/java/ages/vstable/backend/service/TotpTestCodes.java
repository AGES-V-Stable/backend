package ages.vstable.backend.service;

import java.time.Instant;

/**
 * Gera códigos TOTP válidos para testes com o mesmo algoritmo de produção ({@link TotpService}),
 * sem duplicar a lógica criptográfica.
 */
public final class TotpTestCodes {

    private static final long TIME_STEP_SECONDS = 30L;
    private static final TotpService TOTP_SERVICE = new TotpService();

    private TotpTestCodes() {
    }

    public static String newSecret() {
        return TOTP_SERVICE.generateSecret();
    }

    /** Código da janela atual deslocada por {@code windowOffset} (a verificação tolera ±1 janela). */
    public static String code(String secret, int windowOffset) {
        long window = Math.floorDiv(Instant.now().getEpochSecond(), TIME_STEP_SECONDS) + windowOffset;
        return TOTP_SERVICE.generateCodeForWindow(TotpService.decodeBase32(secret), window);
    }

    /** Código válido agora e diferente de {@code previousCode}, para não ser recusado como replay. */
    public static String freshCode(String secret, String previousCode) {
        for (int offset : new int[] {0, 1, -1}) {
            String candidate = code(secret, offset);
            if (!candidate.equals(previousCode)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No fresh TOTP code available in the tolerance window");
    }
}
