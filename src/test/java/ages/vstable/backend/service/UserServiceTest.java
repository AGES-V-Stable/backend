package ages.vstable.backend.service;

import ages.vstable.backend.dto.user.UserResponse;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    private UserService userService;

    @Test
    void findAll_retornaTodosOsRepresentantesSemExporHashDaSenha() {
        userService = new UserService(userRepository);

        UUID empresaId = UUID.randomUUID();
        UserEntity usuario = UserEntity.builder()
                .id(UUID.randomUUID())
                .companyId(empresaId)
                .fullName("Joao da Silva")
                .email("joao@example.com")
                .passwordHash("hash-nunca-deve-vazar")
                .build();

        when(userRepository.findAll()).thenReturn(List.of(usuario));

        List<UserResponse> resultado = userService.findAll();

        assertThat(resultado).hasSize(1);
        UserResponse response = resultado.get(0);
        assertThat(response.getId()).isEqualTo(usuario.getId());
        assertThat(response.getCompanyId()).isEqualTo(empresaId);
        assertThat(response.getFullName()).isEqualTo("Joao da Silva");
        assertThat(response.getEmail()).isEqualTo("joao@example.com");
    }

    @Test
    void findAll_semRepresentantes_retornaListaVazia() {
        userService = new UserService(userRepository);

        when(userRepository.findAll()).thenReturn(List.of());

        List<UserResponse> resultado = userService.findAll();

        assertThat(resultado).isEmpty();
    }
}
