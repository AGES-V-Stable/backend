package ages.vstable.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Anti-replay persistente de TOTP. A escrita condicional é atômica no PostgreSQL
 * e participa da transação Spring existente; não guarda segredo ou código TOTP.
 *
 * <p>Uma linha por principal evita crescimento indefinido do histórico. Passos temporais
 * antigos ou repetidos não podem substituir o último passo confirmado.
 */
@Repository
@RequiredArgsConstructor
public class TotpReplayStore {

    private final JdbcTemplate jdbc;

    public boolean wasConsumed(String email, long timeStep) {
        return jdbc.query("""
                SELECT last_time_step FROM public.totp_used_steps WHERE principal_key = ?
                """, rs -> rs.next() && rs.getLong(1) >= timeStep, principalKey(email));
    }

    public void consume(String email, long timeStep) {
        int written = jdbc.update("""
                INSERT INTO public.totp_used_steps (principal_key, last_time_step)
                VALUES (?, ?)
                ON CONFLICT (principal_key) DO UPDATE SET
                    last_time_step = EXCLUDED.last_time_step
                WHERE totp_used_steps.last_time_step < EXCLUDED.last_time_step
                """, principalKey(email), timeStep);
        if (written != 1) {
            throw new BadCredentialsException("TOTP code has already been used");
        }
    }

    /** SHA-256 da identidade normalizada, sem persistir endereço de e-mail. */
    private static String principalKey(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("TOTP principal is required");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(email.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
