package ages.vstable.backend.service;

import ages.vstable.backend.dto.user.UserResponse;
import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.repository.AdministratorRepository;
import ages.vstable.backend.repository.UserRepository;
import ages.vstable.backend.utils.JwtTokenUtils;
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

    @Mock
    private AdministratorRepository administratorRepository;

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

    @Test
    void getByEmail_returnsUser_whenEmailExists() {
        userService = new UserService(userRepository, administratorRepository);

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
        userService = new UserService(userRepository, administratorRepository);

        String email = "inexistente@example.com";
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getByEmail(email))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessage("User not found");
    }

    @Test
    void loadUserByUsername_returnsUserDetails_whenEmailExists() {
        userService = new UserService(userRepository, administratorRepository);

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
        userService = new UserService(userRepository, administratorRepository);

        String email = "inexistente@example.com";
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.loadUserByUsername(email))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessage("User not found");
    }

    @Test
    void loadUserByUsername_returnsAdministrator_whenEmailBelongsToAdmin() {
        userService = new UserService(userRepository, administratorRepository);

        AdministratorEntity admin = AdministratorEntity.builder()
                .id(UUID.randomUUID())
                .email("admin@vstable.com")
                .passwordHash("hash")
                .build();
        when(administratorRepository.findByEmail("admin@vstable.com")).thenReturn(Optional.of(admin));

        UserDetails result = userService.loadUserByUsername("admin@vstable.com");

        assertThat(result).isSameAs(admin);
        assertThat(result.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
    }

    @Test
    void loadPrincipal_adminAccountType_looksUpAdministrators() {
        userService = new UserService(userRepository, administratorRepository);

        AdministratorEntity admin = AdministratorEntity.builder().email("admin@vstable.com").build();
        when(administratorRepository.findByEmail("admin@vstable.com")).thenReturn(Optional.of(admin));

        assertThat(userService.loadPrincipal("admin@vstable.com", JwtTokenUtils.ACCOUNT_TYPE_ADMIN)).isSameAs(admin);
    }

    @Test
    void loadPrincipal_userAccountType_looksUpCompanyUsers() {
        userService = new UserService(userRepository, administratorRepository);

        UserEntity user = UserEntity.builder().email("joao@example.com").build();
        when(userRepository.findByEmail("joao@example.com")).thenReturn(Optional.of(user));

        assertThat(userService.loadPrincipal("joao@example.com", JwtTokenUtils.ACCOUNT_TYPE_USER)).isSameAs(user);
    }

    @Test
    void loadPrincipal_unknownAdmin_throwsUsernameNotFound() {
        userService = new UserService(userRepository, administratorRepository);

        when(administratorRepository.findByEmail("x@vstable.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.loadPrincipal("x@vstable.com", JwtTokenUtils.ACCOUNT_TYPE_ADMIN))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
