package ages.vstable.backend.service;

import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.enums.AdminAccessLevel;
import ages.vstable.backend.repository.AdministratorRepository;
import ages.vstable.backend.repository.UserRepository;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class InitialAdministratorSeeder {
    private final AdministratorRepository administratorRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public InitialAdministratorSeeder(AdministratorRepository administratorRepository,
                                      UserRepository userRepository,
                                      PasswordEncoder passwordEncoder) {
        this.administratorRepository = administratorRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public boolean seed(String fullName, String email, String password) {
        if (administratorRepository.count() > 0) {
            return false;
        }
        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("The initial administrator email is already in use");
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        administratorRepository.saveAndFlush(AdministratorEntity.builder()
                .fullName(fullName)
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
                .passwordSalt(BCrypt.gensalt())
                .accessLevel(AdminAccessLevel.SUPER_ADMIN)
                .active(true)
                .createdAt(now)
                .updatedAt(now)
                .build());
        return true;
    }
}
