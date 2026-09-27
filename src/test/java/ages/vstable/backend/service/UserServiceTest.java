package ages.vstable.backend.service;

import ages.vstable.backend.dto.user.UserResponse;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private ages.vstable.backend.repository.AdministratorRepository administratorRepository;

    private UserService userService;

    @Test
    void findAll_returnsAllRepresentativesWithoutExposingPasswordHash() {
        userService = new UserService(userRepository, administratorRepository);

        UUID companyId = UUID.randomUUID();
        UserEntity user = UserEntity.builder()
                .id(UUID.randomUUID())
                .companyId(companyId)
                .fullName("Joao da Silva")
                .email("joao@example.com")
                .passwordHash("hash-nunca-deve-vazar")
                .build();

        when(userRepository.findAll()).thenReturn(List.of(user));

        List<UserResponse> result = userService.findAll();

        assertThat(result).hasSize(1);
        UserResponse response = result.get(0);
        assertThat(response.getId()).isEqualTo(user.getId());
        assertThat(response.getCompanyId()).isEqualTo(companyId);
        assertThat(response.getFullName()).isEqualTo("Joao da Silva");
        assertThat(response.getEmail()).isEqualTo("joao@example.com");
    }

    @Test
    void findAll_noRepresentatives_returnsEmptyList() {
        userService = new UserService(userRepository, administratorRepository);

        when(userRepository.findAll()).thenReturn(List.of());

        List<UserResponse> result = userService.findAll();

        assertThat(result).isEmpty();
    }
}
