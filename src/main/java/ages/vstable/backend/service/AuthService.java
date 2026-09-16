package ages.vstable.backend.service;

import ages.vstable.backend.dto.auth.LoginRequest;
import ages.vstable.backend.entity.UsuarioEntity;
import ages.vstable.backend.exception.UnauthorizedException;
import ages.vstable.backend.repository.UsuarioRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

@Service
public class AuthService {

    private final UsuarioRepository usuarioRepository;
    private final byte[] authSecret;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthService(UsuarioRepository usuarioRepository,
                       @Value("${app.auth.secret}") String authSecret) {
        this.usuarioRepository = usuarioRepository;
        this.authSecret = authSecret.getBytes(StandardCharsets.UTF_8);
    }

    public String login(LoginRequest request) {
        UsuarioEntity usuario = usuarioRepository.findByEmailIgnoreCase(request.getEmail().trim())
                .orElseThrow(() -> new UnauthorizedException("E-mail ou senha inválidos"));

        if (!passwordEncoder.matches(request.getPassword(), usuario.getHashSenha())) {
            throw new UnauthorizedException("E-mail ou senha inválidos");
        }

        return createToken(usuario);
    }

    private String createToken(UsuarioEntity usuario) {
        Instant now = Instant.now();
        String payload = "{\"sub\":\"" + usuario.getId()
                + "\",\"iat\":" + now.getEpochSecond()
                + ",\"exp\":" + now.plus(8, ChronoUnit.HOURS).getEpochSecond() + "}";

        try {
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            String header = encoder.encodeToString(
                    "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String body = encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
            String unsignedToken = header + "." + body;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(authSecret, "HmacSHA256"));
            String signature = encoder.encodeToString(mac.doFinal(unsignedToken.getBytes(StandardCharsets.UTF_8)));
            return unsignedToken + "." + signature;
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException("Não foi possível gerar o token de acesso", ex);
        }
    }
}
