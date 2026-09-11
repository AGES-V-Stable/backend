package ages.vstable.backend.service;

import ages.vstable.backend.dto.representante.AcessoCreateRequest;
import ages.vstable.backend.dto.representante.AcessoResponse;
import ages.vstable.backend.entity.ProgressoCadastroEntity;
import ages.vstable.backend.entity.UsuarioEntity;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.repository.ProgressoCadastroRepository;
import ages.vstable.backend.repository.UsuarioRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AcessoService {

    private static final int PROXIMA_ETAPA = 2;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern SENHA_FORTE_PATTERN = Pattern.compile("^(?=.*\\d)(?=.*[^a-zA-Z0-9]).{8,}$");

    private final UsuarioRepository usuarioRepository;
    private final ProgressoCadastroRepository progressoCadastroRepository;
    private final TransactionTemplate transactionTemplate;
    private final String idempotencySecret;
    private final PasswordEncoder passwordEncoder;

    public AcessoService(UsuarioRepository usuarioRepository,
                         ProgressoCadastroRepository progressoCadastroRepository,
                         TransactionTemplate transactionTemplate,
                         @Value("${app.idempotency.secret}") String idempotencySecret,
                         PasswordEncoder passwordEncoder) {
        this.usuarioRepository = usuarioRepository;
        this.progressoCadastroRepository = progressoCadastroRepository;
        this.transactionTemplate = transactionTemplate;
        this.idempotencySecret = idempotencySecret;
        this.passwordEncoder = passwordEncoder;
    }

    public AcessoResponse create(String idempotencyKey, AcessoCreateRequest request) {
        validatePayload(request);

        String payloadHash = hashPayload(request);

        Optional<AcessoResponse> existente = findByIdempotencyKey(idempotencyKey, payloadHash);
        if (existente.isPresent()) {
            return existente.get();
        }

        if (usuarioRepository.existsByEmail(request.getEmail())) {
            throw new ConflictException("E-mail já cadastrado");
        }

        try {
            return transactionTemplate.execute(status ->
                    createUsuarioEProgresso(idempotencyKey, payloadHash, request));
        } catch (DataIntegrityViolationException e) {
            Optional<AcessoResponse> reenvioConcorrente = findByIdempotencyKey(idempotencyKey, payloadHash);
            if (reenvioConcorrente.isPresent()) {
                return reenvioConcorrente.get();
            }
            if (usuarioRepository.existsByEmail(request.getEmail())) {
                throw new ConflictException("E-mail já cadastrado");
            }
            throw e;
        }
    }

    public Optional<AcessoResponse> findById(UUID id) {
        return progressoCadastroRepository.findById(id)
                .map(progresso -> toResponse(progresso, findUsuarioOrThrow(progresso.getUsuarioId())));
    }

    private Optional<AcessoResponse> findByIdempotencyKey(String idempotencyKey, String payloadHash) {
        return progressoCadastroRepository.findByIdempotencyKey(idempotencyKey)
                .map(progresso -> {
                    if (!progresso.getPayloadHash().equals(payloadHash)) {
                        throw new ConflictException("Idempotency-Key já utilizada com um payload diferente");
                    }
                    return toResponse(progresso, findUsuarioOrThrow(progresso.getUsuarioId()));
                });
    }

    private void validatePayload(AcessoCreateRequest request) {
        if (!EMAIL_PATTERN.matcher(request.getEmail()).matches()) {
            throw new UnprocessableEntityException("E-mail em formato inválido");
        }

        String senha = request.getSenha();
        String confirmarSenha = request.getConfirmarSenha();

        if (senha == null || !senha.equals(confirmarSenha)) {
            throw new UnprocessableEntityException("Senha e confirmação não coincidem");
        }

        if (!SENHA_FORTE_PATTERN.matcher(senha).matches()) {
            throw new UnprocessableEntityException("Senha deve ter ao menos 8 caracteres, incluindo número e caractere especial");
        }
    }

    private AcessoResponse createUsuarioEProgresso(String idempotencyKey, String payloadHash, AcessoCreateRequest request) {
        UsuarioEntity usuario = new UsuarioEntity();
        usuario.setNomeCompleto(request.getNomeCompleto());
        usuario.setEmail(request.getEmail());
        System.out.println(request.getSenha());
        System.out.println(passwordEncoder.encode(request.getSenha()));
        usuario.setHashSenha(passwordEncoder.encode(request.getSenha()));
        usuario = usuarioRepository.save(usuario);

        ProgressoCadastroEntity progresso = new ProgressoCadastroEntity();
        progresso.setUsuarioId(usuario.getId());
        progresso.setIdempotencyKey(idempotencyKey);
        progresso.setPayloadHash(payloadHash);
        progresso.setEtapaAtual(PROXIMA_ETAPA);
        progresso = progressoCadastroRepository.save(progresso);

        return toResponse(progresso, usuario);
    }

    private UsuarioEntity findUsuarioOrThrow(UUID usuarioId) {
        return usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new IllegalStateException("Usuario referenciado por progresso_cadastro não encontrado"));
    }

    private String hashPayload(AcessoCreateRequest request) {
        String base = request.getNomeCompleto() + "|" + request.getEmail() + "|" + request.getSenha() + "|" + request.getConfirmarSenha();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(idempotencySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(base.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Algoritmo de hash indisponível", e);
        }
    }

    private AcessoResponse toResponse(ProgressoCadastroEntity progresso, UsuarioEntity usuario) {
        AcessoResponse response = new AcessoResponse();
        response.setToken(progresso.getId());
        response.setEmpresaId(progresso.getEmpresaId());
        response.setEtapaAtual(progresso.getEtapaAtual());
        response.setNomeCompleto(usuario.getNomeCompleto());
        response.setEmail(usuario.getEmail());
        return response;
    }
}
