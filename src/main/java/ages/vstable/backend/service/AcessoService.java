package ages.vstable.backend.service;

import ages.vstable.backend.dto.representante.AcessoCreateRequest;
import ages.vstable.backend.dto.representante.AcessoResponse;
import ages.vstable.backend.entity.ProgressoCadastroEntity;
import ages.vstable.backend.entity.UsuarioEntity;
import ages.vstable.backend.repository.ProgressoCadastroRepository;
import ages.vstable.backend.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AcessoService {

    private static final int PROXIMA_ETAPA = 2;

    private final UsuarioRepository usuarioRepository;
    private final ProgressoCadastroRepository progressoCadastroRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AcessoResponse create(AcessoCreateRequest request) {

        if (usuarioRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("E-mail já cadastrado");
        }

        UsuarioEntity usuario = new UsuarioEntity();
        usuario.setNomeCompleto(request.getNomeCompleto());
        usuario.setEmail(request.getEmail());
        usuario.setHashSenha(passwordEncoder.encode(request.getSenha()));
        usuario = usuarioRepository.save(usuario);

        ProgressoCadastroEntity progresso = new ProgressoCadastroEntity();
        progresso.setUsuarioId(usuario.getId());
        progresso.setEtapaAtual(PROXIMA_ETAPA);
        progresso = progressoCadastroRepository.save(progresso);

        return toResponse(progresso, usuario);
    }

    public Optional<AcessoResponse> findById(UUID id) {
        return progressoCadastroRepository.findById(id)
                .map(progresso -> {
                    UsuarioEntity usuario = usuarioRepository.findById(progresso.getUsuarioId())
                            .orElseThrow(() -> new IllegalStateException("Usuario referenciado por progresso_cadastro não encontrado"));
                    return toResponse(progresso, usuario);
                });
    }

    private AcessoResponse toResponse(ProgressoCadastroEntity progresso, UsuarioEntity usuario) {
        AcessoResponse response = new AcessoResponse();
        response.setToken(progresso.getId());
        response.setEtapaAtual(progresso.getEtapaAtual());
        response.setNomeCompleto(usuario.getNomeCompleto());
        response.setEmail(usuario.getEmail());
        return response;
    }
}
