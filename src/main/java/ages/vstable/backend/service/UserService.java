package ages.vstable.backend.service;

import ages.vstable.backend.dto.representante.UserCreateRequest;
import ages.vstable.backend.dto.representante.UserResponse;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService implements UserDetailsService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserResponse create(UserCreateRequest request){
        // 1. Verifica se as senhas são iguais
        if (!request.getSenha().equals(request.getConfirmarSenha())) {
            throw new IllegalArgumentException(
                    "As senhas não coincidem"
            );
        }

        // 2. Verifica se o e-mail já está cadastrado
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException(
                    "E-mail já cadastrado"
            );
        }

        // 3. Cria a entidade
        UserEntity user = new UserEntity();

        user.setNomeCompleto(request.getNomeCompleto());
        user.setEmail(request.getEmail());

        // 4. Criptografa a senha
        user.setHashSenha(
                passwordEncoder.encode(request.getSenha())
        );

        // 5. Define as datas
        OffsetDateTime now = OffsetDateTime.now();

        user.setCriadoEm(now);
        user.setAtualizadoEm(now);

        // 6. Salva no banco
        UserEntity savedUser = userRepository.save(user);

        // 7. Converte para response
        return toResponse(savedUser);
    }

    public List<UserResponse> findAll() {
        return userRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public UserEntity getByEmail(String email) throws UsernameNotFoundException {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    @Override
    @NullMarked
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return userRepository.findByEmail(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    private UserResponse toResponse(UserEntity entity) {
        return new UserResponse(
                entity.getId(),
                entity.getNomeCompleto(),
                entity.getEmail()
        );
    }
}
