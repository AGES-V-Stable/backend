package ages.vstable.backend.service;

import ages.vstable.backend.dto.authentication.LoginResponseDTO;
import ages.vstable.backend.dto.authentication.TwoFactorSetupResponseDTO;
import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.repository.AdministratorRepository;
import ages.vstable.backend.repository.UserRepository;
import ages.vstable.backend.utils.JwtTokenUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class TwoFactorAuthService {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCKOUT_SECONDS = 15 * 60;

    private final TotpService totpService;
    private final UserRepository userRepository;
    private final AdministratorRepository administratorRepository;
    private final JwtTokenUtils jwtTokenUtils;
    private final UserService userService;

    // Protecoes em memoria contra replay attack e brute force
    private final Map<String, String> lastUsedCodeByAccount = new ConcurrentHashMap<>();
    private final Map<String, Integer> failedAttemptsByAccount = new ConcurrentHashMap<>();
    private final Map<String, Instant> lockedUntilByAccount = new ConcurrentHashMap<>();

    @Transactional
    public TwoFactorSetupResponseDTO setupTwoFactor(UserDetails principal) {
        return setupTwoFactor(principal, null);
    }

    /**
     * Inicia a configuração do 2FA.
     * Se o 2FA já estiver ativo, exige o código TOTP atual (currentCode) para
     * autorizar a geração de um novo QR Code (troca de aparelho).
     */
    @Transactional
    public TwoFactorSetupResponseDTO setupTwoFactor(UserDetails principal, String currentCode) {
        if (isTwoFactorAlreadyEnabled(principal)) {
            if (currentCode == null || currentCode.isBlank()) {
                throw new BadCredentialsException("O código TOTP atual é obrigatório para reconfigurar o 2FA");
            }
            String currentSecret = getSecret(principal);
            verifyTotpWithSecurityChecks(principal.getUsername(), currentSecret, currentCode);
        }
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
        assertNotLocked(principal.getUsername());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        if (principal instanceof UserEntity user) {
            if (user.getTwoFactorSecret() == null || user.getTwoFactorSecret().isBlank()) {
                throw new UnprocessableEntityException("2FA setup has not been initiated for this account");
            }
            verifyTotpWithSecurityChecks(user.getEmail(), user.getTwoFactorSecret(), code);
            user.setTwoFactorEnabled(true);
            user.setUpdatedAt(now);
            userRepository.save(user);
        } else if (principal instanceof AdministratorEntity admin) {
            if (admin.getTwoFactorSecret() == null || admin.getTwoFactorSecret().isBlank()) {
                throw new UnprocessableEntityException("2FA setup has not been initiated for this account");
            }
            verifyTotpWithSecurityChecks(admin.getEmail(), admin.getTwoFactorSecret(), code);
            admin.setTwoFactorEnabled(true);
            admin.setUpdatedAt(now);
            administratorRepository.save(admin);
        } else {
            throw new IllegalArgumentException("Unsupported principal type");
        }
    }

    /**
     * Verifica se a conta exige 2FA no login:
     * - Usuário com twoFactorEnabled = true
     * - Administrador com twoFactorEnabled = true OU require2fa = true
     */
    public boolean requiresTwoFactorOnLogin(UserDetails principal) {
        if (principal instanceof UserEntity user) {
            return Boolean.TRUE.equals(user.getTwoFactorEnabled());
        }
        if (principal instanceof AdministratorEntity admin) {
            return Boolean.TRUE.equals(admin.getTwoFactorEnabled())
                    || Boolean.TRUE.equals(admin.getRequire2fa());
        }
        return false;
    }

    /**
     * Etapa 1 do Login quando 2FA é exigido: gera o challengeToken.
     * Só retorna otpauthUri se a conta ainda NÃO tiver twoFactorEnabled = true (ex:
     * 1º acesso de Admin).
     */
    @Transactional
    public LoginResponseDTO createLoginChallenge(UserDetails principal) {
        String challengeToken = jwtTokenUtils.generateChallengeToken(principal);
        String otpauthUri = null;
        if (!isTwoFactorAlreadyEnabled(principal)) {
            // Conta obrigada a usar 2FA (ex: Admin com require_2fa = true), mas ainda no 1º acesso
            String secret = getSecret(principal);
            if (secret == null || secret.isBlank()) {
                secret = totpService.generateSecret();
                savePendingSecret(principal, secret);
            }
            otpauthUri = totpService.buildOtpAuthUri(principal.getUsername(), secret);
        }
        return LoginResponseDTO.builder()
                .requires2fa(true)
                .challengeToken(challengeToken)
                .otpauthUri(otpauthUri)
                .build();
    }

    /**
     * Etapa 2 do Login (/v1/auth/login/verify-2fa): valida o challengeToken e o
     * código TOTP, e retorna o UserDetails autenticado para emissão do JWT definitivo.
     */
    @Transactional
    public UserDetails verifyLoginChallenge(String challengeToken, String code) {
        if (challengeToken == null || !jwtTokenUtils.validateChallengeToken(challengeToken)) {
            throw new BadCredentialsException("Invalid or expired 2FA challenge token");
        }
        String email = jwtTokenUtils.getUsernameFromToken(challengeToken);
        String accountType = jwtTokenUtils.getAccountTypeFromToken(challengeToken);
        assertNotLocked(email);
        UserDetails principal = userService.loadPrincipal(email, accountType);
        if (!principal.isEnabled()) {
            throw new LockedException("Account is disabled");
        }
        String secret = getSecret(principal);
        if (secret == null || secret.isBlank()) {
            throw new BadCredentialsException("2FA is not configured for this account");
        }
        verifyTotpWithSecurityChecks(email, secret, code);
        // Se era o 1º acesso de um Admin (twoFactorEnabled ainda era false), marca como true após validar!
        if (!isTwoFactorAlreadyEnabled(principal) && principal instanceof AdministratorEntity admin) {
            admin.setTwoFactorEnabled(true);
            admin.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            administratorRepository.save(admin);
        }
        return principal;
    }

    /**
     * Valida o código TOTP do usuário autenticado antes de autorizar operações
     * críticas (como criação de transferência na Avenia), sem consumir o código.
     * O consumo definitivo deve ser realizado ao final da operação via
     * {@link #consumeTransferTotp(UUID, String)}.
     */
    public void validateTransferTotp(UUID userId, String code) {
        UserEntity user = loadAuthenticatedUserForTotp(userId);
        assertNotLocked(user.getEmail());

        if (!Boolean.TRUE.equals(user.getTwoFactorEnabled())
                || user.getTwoFactorSecret() == null
                || user.getTwoFactorSecret().isBlank()) {
            throw new UnprocessableEntityException(
                    "Usuário não possui 2FA ativo; configure e ative o 2FA antes de realizar transferências");
        }
        if (code == null || code.isBlank()) {
            throw new UnprocessableEntityException(
                    "O código TOTP de 6 dígitos é obrigatório para confirmar a transferência");
        }
        validateTotpWithoutConsuming(user.getEmail(), user.getTwoFactorSecret(), code);
    }

    /**
     * Consome o código TOTP após a conclusão bem-sucedida da operação crítica,
     * garantindo que o token não seja gasto caso a operação falhe em estado de erro.
     */
    public void consumeTransferTotp(UUID userId, String code) {
        UserEntity user = loadAuthenticatedUserForTotp(userId);
        consumeTotpCode(user.getEmail(), code);
    }

    private UserEntity loadAuthenticatedUserForTotp(UUID userId) {
        if (userId == null) {
            throw new BadCredentialsException("User not authenticated");
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new BadCredentialsException("Authenticated user not found"));
    }

    private void verifyTotpWithSecurityChecks(String email, String secret, String code) {
        validateTotpWithoutConsuming(email, secret, code);
        consumeTotpCode(email, code);
    }

    private void validateTotpWithoutConsuming(String email, String secret, String code) {
        String normalizedCode = code == null ? "" : code.trim();
        if (!totpService.verifyCode(secret, normalizedCode)) {
            recordFailedAttempt(email);
            throw new BadCredentialsException("Invalid 2FA verification code");
        }
        // Anti-Replay: impede reutilizar exatamente o mesmo código de 6 dígitos já consumido
        String previousCode = lastUsedCodeByAccount.get(email);
        if (normalizedCode.equals(previousCode)) {
            throw new BadCredentialsException("TOTP code has already been used");
        }
    }

    private void consumeTotpCode(String email, String code) {
        String normalizedCode = code == null ? "" : code.trim();
        lastUsedCodeByAccount.put(email, normalizedCode);
        failedAttemptsByAccount.remove(email);
        lockedUntilByAccount.remove(email);
    }

    private void assertNotLocked(String email) {
        Instant lockedUntil = lockedUntilByAccount.get(email);
        if (lockedUntil != null) {
            if (Instant.now().isBefore(lockedUntil)) {
                throw new LockedException("Too many failed 2FA attempts. Try again later.");
            }
            lockedUntilByAccount.remove(email);
            failedAttemptsByAccount.remove(email);
        }
    }

    private void recordFailedAttempt(String email) {
        int attempts = failedAttemptsByAccount.merge(email, 1, Integer::sum);
        if (attempts >= MAX_FAILED_ATTEMPTS) {
            lockedUntilByAccount.put(email, Instant.now().plusSeconds(LOCKOUT_SECONDS));
        }
    }

    private boolean isTwoFactorAlreadyEnabled(UserDetails principal) {
        if (principal instanceof UserEntity user) {
            return Boolean.TRUE.equals(user.getTwoFactorEnabled());
        }
        if (principal instanceof AdministratorEntity admin) {
            return Boolean.TRUE.equals(admin.getTwoFactorEnabled());
        }
        return false;
    }

    private String getSecret(UserDetails principal) {
        if (principal instanceof UserEntity user) {
            return user.getTwoFactorSecret();
        }
        if (principal instanceof AdministratorEntity admin) {
            return admin.getTwoFactorSecret();
        }
        return null;
    }

    private void savePendingSecret(UserDetails principal, String secret) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (principal instanceof AdministratorEntity admin) {
            admin.setTwoFactorSecret(secret);
            admin.setTwoFactorEnabled(false);
            admin.setUpdatedAt(now);
            administratorRepository.save(admin);
        } else if (principal instanceof UserEntity user) {
            user.setTwoFactorSecret(secret);
            user.setTwoFactorEnabled(false);
            user.setUpdatedAt(now);
            userRepository.save(user);
        }
    }
}
