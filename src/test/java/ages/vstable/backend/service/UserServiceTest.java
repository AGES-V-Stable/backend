package ages.vstable.backend.service;

import ages.vstable.backend.dto.user.UserResponse;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    private UserService userService;

    @Test
    void findAll_returnsAllRepresentativesWithoutExposingPasswordHash() {
        userService = new UserService(userRepository);

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
        userService = new UserService(userRepository);

        when(userRepository.findAll()).thenReturn(List.of());

        List<UserResponse> result = userService.findAll();

        assertThat(result).isEmpty();
    }

    @Test
    void getByEmail_returnsUser_whenEmailExists() {
        userService = new UserService(userRepository);

        String email = "joao@example.com";
        UserEntity user = UserEntity.builder()
                .id(UUID.randomUUID())
                .fullName("Joao da Silva")
                .email(email)
                .build();

        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));

        UserEntity result = userService.getByEmail(email);

        assertThat(result).isSameAs(user);
    }

    @Test
    void getByEmail_throwsUsernameNotFound_whenEmailDoesNotExist() {
        userService = new UserService(userRepository);

        String email = "inexistente@example.com";
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getByEmail(email))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessage("User not found");
    }

    @Test
    void loadUserByUsername_returnsUserDetails_whenEmailExists() {
        userService = new UserService(userRepository);

        String email = "joao@example.com";
        String passwordHash = "hash-da-senha";
        UserEntity user = UserEntity.builder()
                .id(UUID.randomUUID())
                .fullName("Joao da Silva")
                .email(email)
                .passwordHash(passwordHash)
                .build();

        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));

        UserDetails result = userService.loadUserByUsername(email);

        assertThat(result.getUsername()).isEqualTo(email);
        assertThat(result.getPassword()).isEqualTo(passwordHash);
    }

    @Test
    void loadUserByUsername_throwsUsernameNotFound_whenEmailDoesNotExist() {
        userService = new UserService(userRepository);

        String email = "inexistente@example.com";
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.loadUserByUsername(email))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessage("User not found");
    }
}
