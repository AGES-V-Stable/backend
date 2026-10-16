package ages.vstable.backend.service;

import ages.vstable.backend.dto.authentication.TwoFactorSetupResponseDTO;
import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.repository.AdministratorRepository;
import ages.vstable.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;


@Service
@RequiredArgsConstructor
public class TwoFactorAuthService {
    private final TotpService totpService;
    private final UserRepository userRepository;
    private final AdministratorRepository administratorRepository;

    @Transactional
    public TwoFactorSetupResponseDTO setupTwoFactor(UserDetails principal) {
        String secret = totpService.generateSecret();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        if (principal instanceof UserEntity user) {
            user.setTwoFactorSecret(secret);
            user.setTwoFactorEnabled(false);
            user.setUpdatedAt(now);
            userRepository.save(user);
        } else if (principal instanceof AdministratorEntity admin) {
            admin.setTwoFactorSecret(secret);
            admin.setTwoFactorEnabled(false);
            admin.setUpdatedAt(now);
            administratorRepository.save(admin);
        } else {
            throw new IllegalArgumentException("Unsupported principal type");
        }
        String otpauthUri = totpService.buildOtpAuthUri(principal.getUsername(), secret);
        return TwoFactorSetupResponseDTO.builder()
                .otpauthUri(otpauthUri)
                .twoFactorEnabled(false)
                .build();
    }
    @Transactional
    public void activateTwoFactor(UserDetails principal, String code) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (principal instanceof UserEntity user) {
            if (user.getTwoFactorSecret() == null || user.getTwoFactorSecret().isBlank()) {
                throw new UnprocessableEntityException("2FA setup has not been initiated for this account");
            }
            if (!totpService.verifyCode(user.getTwoFactorSecret(), code)) {
                throw new BadCredentialsException("Invalid 2FA verification code");
            }
            user.setTwoFactorEnabled(true);
            user.setUpdatedAt(now);
            userRepository.save(user);
        } else if (principal instanceof AdministratorEntity admin) {
            if (admin.getTwoFactorSecret() == null || admin.getTwoFactorSecret().isBlank()) {
                throw new UnprocessableEntityException("2FA setup has not been initiated for this account");
            }
            if (!totpService.verifyCode(admin.getTwoFactorSecret(), code)) {
                throw new BadCredentialsException("Invalid 2FA verification code");
            }
            admin.setTwoFactorEnabled(true);
            admin.setUpdatedAt(now);
            administratorRepository.save(admin);
        } else {
            throw new IllegalArgumentException("Unsupported principal type");
        }
    }
}