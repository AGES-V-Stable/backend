package ages.vstable.backend.service;

import ages.vstable.backend.dto.user.UserResponse;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.repository.AdministratorRepository;
import ages.vstable.backend.repository.UserRepository;
import ages.vstable.backend.utils.JwtTokenUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService implements UserDetailsService {

    private final UserRepository userRepository;
    private final AdministratorRepository administratorRepository;

    public UserEntity getByEmail(String email) throws UsernameNotFoundException {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    /**
     * Usado no login: procura primeiro um administrador e depois um usuário de
     * empresa. O onboarding recusa e-mails de administradores, então o mesmo
     * e-mail não existe nas duas tabelas.
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return administratorRepository.findByEmail(username)
                .<UserDetails>map(admin -> admin)
                .or(() -> userRepository.findByEmail(username))
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    /**
     * Usado pelo filtro JWT: o tipo de conta vem do próprio token, então a
     * busca vai direto na tabela certa.
     */
    public UserDetails loadPrincipal(String email, String accountType) throws UsernameNotFoundException {
        if (JwtTokenUtils.ACCOUNT_TYPE_ADMIN.equals(accountType)) {
            return administratorRepository.findByEmail(email)
                    .orElseThrow(() -> new UsernameNotFoundException("Administrator not found"));
        }
        return getByEmail(email);
    }

    public List<UserResponse> findAll() {
        return userRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private UserResponse toResponse(UserEntity entity) {
        UserResponse response = new UserResponse();

        response.setId(entity.getId());
        response.setCompanyId(entity.getCompanyId());
        response.setFullName(entity.getFullName());
        response.setEmail(entity.getEmail());
        response.setCreatedAt(entity.getCreatedAt());
        response.setUpdatedAt(entity.getUpdatedAt());

        return response;
    }
}
