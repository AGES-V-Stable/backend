package ages.vstable.backend.service;

import ages.vstable.backend.dto.onboarding.AccountRequestDTO;
import ages.vstable.backend.dto.onboarding.CompanyRegistrationRequestDTO;
import ages.vstable.backend.dto.onboarding.OnboardingRequestDTO;
import ages.vstable.backend.dto.onboarding.OnboardingResponseDTO;
import ages.vstable.backend.dto.onboarding.OnboardingStatusResponse;
import ages.vstable.backend.entity.AveniaKycVerificationEntity;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.ComplianceStatus;
import ages.vstable.backend.entity.enums.MembershipStatus;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.repository.AdministratorRepository;
import ages.vstable.backend.repository.AveniaKycVerificationRepository;
import ages.vstable.backend.repository.CompanyRepository;
import ages.vstable.backend.repository.ComplianceDocumentRepository;
import ages.vstable.backend.repository.UserRepository;
import ages.vstable.backend.support.TestCompanyUsers;
import ages.vstable.backend.utils.JwtTokenUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OnboardingServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AdministratorRepository administratorRepository;

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private AveniaKycVerificationRepository aveniaKycVerificationRepository;

    @Mock
    private ComplianceDocumentRepository complianceDocumentRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenUtils jwtTokenUtils;

    @Mock
    private CompanyMembershipService companyMembershipService;

    private OnboardingService onboardingService;

    @BeforeEach
    void setUp() {
        CompanyService companyService = new CompanyService(
                companyRepository, complianceDocumentRepository, new CompanyDataValidator(), userRepository);
        onboardingService = new OnboardingService(
                userRepository, administratorRepository, aveniaKycVerificationRepository,
                new CompanyDataValidator(), passwordEncoder, companyService, jwtTokenUtils, new TotpService(),
                companyMembershipService);
    }

    @Test
    void performOnboarding_createsCompanyUserAndPendingKyc() {
        OnboardingRequestDTO request = validRequest();
        UUID companyId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID kycId = UUID.randomUUID();

        when(companyRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            CompanyEntity company = invocation.getArgument(0);
            company.setId(companyId);
            return company;
        });
        when(userRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(userId);
            return user;
        });
        when(aveniaKycVerificationRepository.save(any())).thenAnswer(invocation -> {
            AveniaKycVerificationEntity kyc = invocation.getArgument(0);
            kyc.setId(kycId);
            return kyc;
        });
        when(passwordEncoder.encode("Senha@123")).thenReturn("hash-bcrypt");
        when(jwtTokenUtils.generateToken(any())).thenReturn("jwt-token-123");

        OnboardingResponseDTO response = onboardingService.performOnboarding(request);

        assertThat(response.getCompanyId()).isEqualTo(companyId);
        assertThat(response.getUserId()).isEqualTo(userId);
        assertThat(response.getKycVerificationId()).isEqualTo(kycId);
        assertThat(response.getAccessToken()).isEqualTo("jwt-token-123");

        ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
        org.mockito.Mockito.verify(userRepository).saveAndFlush(userCaptor.capture());
        UserEntity savedUser = userCaptor.getValue();
        assertThat(savedUser.getFullName()).isEqualTo("Joao da Silva");
        assertThat(savedUser.getCompanyId()).isEqualTo(companyId);
        assertThat(savedUser.getPasswordHash()).isEqualTo("hash-bcrypt");
        assertThat(savedUser.getPasswordSalt()).isNotBlank();

        ArgumentCaptor<AveniaKycVerificationEntity> kycCaptor = ArgumentCaptor.forClass(AveniaKycVerificationEntity.class);
        org.mockito.Mockito.verify(aveniaKycVerificationRepository).save(kycCaptor.capture());
        assertThat(kycCaptor.getValue().getUserId()).isEqualTo(userId);
        assertThat(kycCaptor.getValue().getStatus()).isEqualTo(ComplianceStatus.PENDING);
    }

    @Test
    void performOnboarding_assignsCreatedUserAsOwnerOfCreatedCompany() {
        UUID companyId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        stubCompanyAndUserPersistence(companyId, userId);

        onboardingService.performOnboarding(validRequest());

        InOrder order = inOrder(userRepository, companyMembershipService, aveniaKycVerificationRepository);
        order.verify(userRepository).saveAndFlush(any());
        order.verify(companyMembershipService).assignOwner(eq(userId), eq(companyId), any());
        order.verify(aveniaKycVerificationRepository).save(any());
    }

    @Test
    void performOnboarding_ownerAssignmentFails_doesNotCreateKycNorIssueToken() {
        stubCompanyAndUserPersistence(UUID.randomUUID(), UUID.randomUUID());
        when(companyMembershipService.assignOwner(any(), any(), any()))
                .thenThrow(new IllegalStateException("owner assignment failed"));

        assertThatThrownBy(() -> onboardingService.performOnboarding(validRequest()))
                .isInstanceOf(IllegalStateException.class);

        verify(aveniaKycVerificationRepository, never()).save(any());
        verify(jwtTokenUtils, never()).generateToken(any());
    }

    @Test
    void performOnboarding_emailAlreadyRegistered_throwsConflict() {
        when(userRepository.existsByEmail("joao@example.com")).thenReturn(true);

        assertThatThrownBy(() -> onboardingService.performOnboarding(validRequest()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void performOnboarding_emailBelongsToAdministrator_throwsConflict() {
        when(administratorRepository.existsByEmail("joao@example.com")).thenReturn(true);

        assertThatThrownBy(() -> onboardingService.performOnboarding(validRequest()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void getCurrentOnboarding_returnsLatestVerificationProgress() {
        UUID userId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        UUID kycId = UUID.randomUUID();
        UserEntity user = UserEntity.builder().id(userId).companyId(companyId).build();
        AveniaKycVerificationEntity kyc = AveniaKycVerificationEntity.builder()
                .id(kycId).userId(userId).documentId("doc-1").status(ComplianceStatus.PENDING).build();
        when(aveniaKycVerificationRepository.findFirstByUserIdOrderByCreatedAtDesc(userId)).thenReturn(Optional.of(kyc));

        OnboardingStatusResponse result = onboardingService.getCurrentOnboarding(user);

        assertThat(result.getKycVerificationId()).isEqualTo(kycId);
        assertThat(result.getCompanyId()).isEqualTo(companyId);
        assertThat(result.getStatus()).isEqualTo(ComplianceStatus.PENDING);
        assertThat(result.isDocumentSubmitted()).isTrue();
        assertThat(result.isLivenessSubmitted()).isFalse();
    }

    @Test
    void getCurrentOnboarding_userWithActiveMembership_returnsTheCompanyOfTheMembership() {
        UUID accountCompanyId = UUID.randomUUID();
        UUID membershipCompanyId = UUID.randomUUID();
        UserEntity user = UserEntity.builder().id(UUID.randomUUID()).companyId(accountCompanyId).build();
        TestCompanyUsers.withMembership(user, membershipCompanyId, CompanyRole.OWNER, MembershipStatus.ACTIVE);
        when(aveniaKycVerificationRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId()))
                .thenReturn(Optional.of(AveniaKycVerificationEntity.builder()
                        .id(UUID.randomUUID()).userId(user.getId()).status(ComplianceStatus.PENDING).build()));

        OnboardingStatusResponse result = onboardingService.getCurrentOnboarding(user);

        assertThat(result.getCompanyId()).isEqualTo(membershipCompanyId);
    }

    @Test
    void getCurrentOnboarding_userWithRemovedMembership_returnsNoCompany() {
        UserEntity user = UserEntity.builder().id(UUID.randomUUID()).companyId(UUID.randomUUID()).build();
        TestCompanyUsers.withMembership(user, user.getCompanyId(), CompanyRole.OPERATOR, MembershipStatus.REMOVED);
        when(aveniaKycVerificationRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId()))
                .thenReturn(Optional.of(AveniaKycVerificationEntity.builder()
                        .id(UUID.randomUUID()).userId(user.getId()).status(ComplianceStatus.PENDING).build()));

        OnboardingStatusResponse result = onboardingService.getCurrentOnboarding(user);

        assertThat(result.getCompanyId()).isNull();
    }

    @Test
    void getCurrentOnboarding_withoutVerification_throwsNotFound() {
        UserEntity user = UserEntity.builder().id(UUID.randomUUID()).build();
        when(aveniaKycVerificationRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> onboardingService.getCurrentOnboarding(user))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void performOnboarding_cnpjAlreadyRegistered_throwsConflict() {
        when(companyRepository.existsByCnpj("11222333000181")).thenReturn(true);

        assertThatThrownBy(() -> onboardingService.performOnboarding(validRequest()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void performOnboarding_emailConstraintRaceCondition_returnsConflict() {
        // Arrange: outra requisição gravou o mesmo e-mail depois da verificação prévia.
        when(companyRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.saveAndFlush(any())).thenThrow(uniqueViolation(OnboardingService.EMAIL_UNIQUE_CONSTRAINT));

        // Act / Assert
        assertThatThrownBy(() -> onboardingService.performOnboarding(validRequest()))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Email already registered");
        verify(companyMembershipService, never()).assignOwner(any(), any(), any());
    }

    @Test
    void performOnboarding_cnpjConstraintRaceCondition_returnsConflictWithoutCreatingTheUser() {
        when(companyRepository.saveAndFlush(any())).thenThrow(uniqueViolation("companies_cnpj_key"));

        assertThatThrownBy(() -> onboardingService.performOnboarding(validRequest()))
                .isInstanceOf(ConflictException.class)
                .hasMessage("CNPJ already registered");
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void performOnboarding_unexpectedIntegrityViolation_isNotReportedAsConflict() {
        when(companyRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        DataIntegrityViolationException failure = uniqueViolation("users_full_name_check");
        when(userRepository.saveAndFlush(any())).thenThrow(failure);

        assertThatThrownBy(() -> onboardingService.performOnboarding(validRequest())).isSameAs(failure);
    }

    @Test
    void performOnboarding_withTwoFactor_returnsTheSetupUriWithoutEnablingIt() {
        stubCompanyAndUserPersistence(UUID.randomUUID(), UUID.randomUUID());
        OnboardingRequestDTO request = validRequest();
        request.setEnableTwoFactor(true);

        OnboardingResponseDTO response = onboardingService.performOnboarding(request);

        assertThat(response.isTwoFactorSetupRequired()).isTrue();
        assertThat(response.getOtpauthUri()).startsWith("otpauth://");
        ArgumentCaptor<UserEntity> saved = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getTwoFactorEnabled()).isFalse();
        assertThat(saved.getValue().getTwoFactorSecret()).isNotBlank();
    }

    @Test
    void performOnboarding_passwordAndConfirmationMismatch_throwsUnprocessable() {
        OnboardingRequestDTO request = validRequest();
        request.setConfirmPassword("Outra@123");

        assertThatThrownBy(() -> onboardingService.performOnboarding(request))
                .isInstanceOf(UnprocessableEntityException.class);
    }

    @Test
    void performOnboarding_weakPassword_throwsUnprocessable() {
        OnboardingRequestDTO request = validRequest();
        request.setPassword("fraca");
        request.setConfirmPassword("fraca");

        assertThatThrownBy(() -> onboardingService.performOnboarding(request))
                .isInstanceOf(UnprocessableEntityException.class);
    }

    @Test
    void performOnboarding_passwordWithoutNumber_throwsUnprocessable() {
        OnboardingRequestDTO request = validRequest();
        request.setPassword("Senha@abc");
        request.setConfirmPassword("Senha@abc");

        assertThatThrownBy(() -> onboardingService.performOnboarding(request))
                .isInstanceOf(UnprocessableEntityException.class);
    }

    @Test
    void performOnboarding_passwordWithoutSpecialCharacter_throwsUnprocessable() {
        OnboardingRequestDTO request = validRequest();
        request.setPassword("Senha1234");
        request.setConfirmPassword("Senha1234");

        assertThatThrownBy(() -> onboardingService.performOnboarding(request))
                .isInstanceOf(UnprocessableEntityException.class);
    }

    // --- Conta e empresa em etapas separadas (backend#64) ---

    @Test
    void createAccount_savesUserWithoutCompanyMembershipOrKyc_andReturnsAToken() {
        // Arrange
        UUID userId = UUID.randomUUID();
        stubCompanyAndUserPersistence(UUID.randomUUID(), userId);

        // Act
        OnboardingResponseDTO response = onboardingService.createAccount(accountRequest());

        // Assert
        assertThat(response.getUserId()).isEqualTo(userId);
        assertThat(response.getCompanyId()).isNull();
        assertThat(response.getKycVerificationId()).isNull();
        assertThat(response.getAccessToken()).isEqualTo("jwt-token-123");
        assertThat(response.isTwoFactorSetupRequired()).isFalse();
        ArgumentCaptor<UserEntity> saved = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getCompanyId()).isNull();
        assertThat(saved.getValue().getFullName()).isEqualTo("Joao da Silva");
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("hash-bcrypt");
        assertThat(saved.getValue().getPasswordSalt()).isNotBlank();
        verify(companyRepository, never()).saveAndFlush(any());
        verify(companyMembershipService, never()).assignOwner(any(), any(), any());
        verify(aveniaKycVerificationRepository, never()).save(any());
    }

    @Test
    void createAccount_withTwoFactor_returnsTheSetupUri() {
        stubCompanyAndUserPersistence(UUID.randomUUID(), UUID.randomUUID());
        AccountRequestDTO request = accountRequest();
        request.setEnableTwoFactor(true);

        OnboardingResponseDTO response = onboardingService.createAccount(request);

        assertThat(response.isTwoFactorSetupRequired()).isTrue();
        assertThat(response.getOtpauthUri()).startsWith("otpauth://");
    }

    @Test
    void createAccount_emailAlreadyRegistered_throwsConflictWithoutSaving() {
        when(userRepository.existsByEmail("joao@example.com")).thenReturn(true);

        assertThatThrownBy(() -> onboardingService.createAccount(accountRequest()))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Email already registered");
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void createAccount_emailBelongsToAdministrator_throwsConflictWithoutSaving() {
        when(administratorRepository.existsByEmail("joao@example.com")).thenReturn(true);

        assertThatThrownBy(() -> onboardingService.createAccount(accountRequest()))
                .isInstanceOf(ConflictException.class);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void createAccount_concurrentSameEmail_throwsConflict() {
        when(passwordEncoder.encode(any())).thenReturn("hash-bcrypt");
        when(userRepository.saveAndFlush(any())).thenThrow(uniqueViolation(OnboardingService.EMAIL_UNIQUE_CONSTRAINT));

        assertThatThrownBy(() -> onboardingService.createAccount(accountRequest()))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Email already registered");
    }

    @Test
    void createAccount_passwordAndConfirmationMismatch_throwsUnprocessableWithoutSaving() {
        AccountRequestDTO request = accountRequest();
        request.setConfirmPassword("Outra@123");

        assertThatThrownBy(() -> onboardingService.createAccount(request))
                .isInstanceOf(UnprocessableEntityException.class);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void createAccount_weakPassword_throwsUnprocessable() {
        AccountRequestDTO request = accountRequest();
        request.setPassword("fraca");
        request.setConfirmPassword("fraca");

        assertThatThrownBy(() -> onboardingService.createAccount(request))
                .isInstanceOf(UnprocessableEntityException.class);
    }

    @Test
    void createAccount_passwordLongerThan72Utf8Bytes_throwsUnprocessableWithoutSaving() {
        // Arrange: 37 caracteres "é" ocupam 74 bytes, acima do limite do BCrypt
        AccountRequestDTO request = accountRequest();
        String tooLong = "1@" + "é".repeat(36);
        request.setPassword(tooLong);
        request.setConfirmPassword(tooLong);

        // Act / Assert
        assertThatThrownBy(() -> onboardingService.createAccount(request))
                .isInstanceOf(UnprocessableEntityException.class);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void registerCompany_userWithoutCompany_createsCompanyOwnerMembershipAndPendingKyc() {
        // Arrange
        UUID companyId = UUID.randomUUID();
        UUID kycId = UUID.randomUUID();
        UserEntity user = UserEntity.builder().id(UUID.randomUUID()).build();
        stubCompanyAndUserPersistence(companyId, user.getId());
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(aveniaKycVerificationRepository.save(any())).thenAnswer(invocation -> {
            AveniaKycVerificationEntity kyc = invocation.getArgument(0);
            kyc.setId(kycId);
            return kyc;
        });

        // Act
        OnboardingResponseDTO response = onboardingService.registerCompany(user, companyRequest());

        // Assert
        assertThat(response.getCompanyId()).isEqualTo(companyId);
        assertThat(response.getUserId()).isEqualTo(user.getId());
        assertThat(response.getKycVerificationId()).isEqualTo(kycId);
        assertThat(response.getAccessToken()).isNull();
        assertThat(user.getCompanyId()).isEqualTo(companyId);
        InOrder order = inOrder(companyRepository, userRepository, companyMembershipService,
                aveniaKycVerificationRepository);
        order.verify(companyRepository).saveAndFlush(any());
        order.verify(userRepository).saveAndFlush(user);
        order.verify(companyMembershipService).assignOwner(eq(user.getId()), eq(companyId), any());
        order.verify(aveniaKycVerificationRepository).save(any());
        verify(jwtTokenUtils, never()).generateToken(any());
    }

    @Test
    void registerCompany_userWithActiveMembership_throwsConflictWithoutCreatingAnything() {
        UserEntity user = TestCompanyUsers.member(UUID.randomUUID(), CompanyRole.OPERATOR);

        assertThatThrownBy(() -> onboardingService.registerCompany(user, companyRequest()))
                .isInstanceOf(ConflictException.class)
                .hasMessage("User already belongs to a company")
                .extracting("code").isEqualTo("USER_ALREADY_IN_COMPANY");
        verify(companyRepository, never()).saveAndFlush(any());
        verify(companyMembershipService, never()).assignOwner(any(), any(), any());
    }

    @Test
    void registerCompany_legacyUserWithCompanyAndNoMembership_throwsConflict() {
        UserEntity user = UserEntity.builder().id(UUID.randomUUID()).companyId(UUID.randomUUID()).build();

        assertThatThrownBy(() -> onboardingService.registerCompany(user, companyRequest()))
                .isInstanceOf(ConflictException.class);
        verify(companyRepository, never()).saveAndFlush(any());
    }

    @Test
    void registerCompany_userWhoseMembershipWasRemoved_canRegisterANewCompany() {
        UUID newCompanyId = UUID.randomUUID();
        UserEntity user = TestCompanyUsers.removedMember(UUID.randomUUID(), CompanyRole.OPERATOR);
        stubCompanyAndUserPersistence(newCompanyId, user.getId());
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        OnboardingResponseDTO response = onboardingService.registerCompany(user, companyRequest());

        assertThat(response.getCompanyId()).isEqualTo(newCompanyId);
        verify(companyMembershipService).assignOwner(eq(user.getId()), eq(newCompanyId), any());
    }

    @Test
    void registerCompany_cnpjAlreadyRegistered_isAConflictAndNeverJoinsTheExistingCompany() {
        UserEntity user = UserEntity.builder().id(UUID.randomUUID()).build();
        when(companyRepository.existsByCnpj("11222333000181")).thenReturn(true);

        assertThatThrownBy(() -> onboardingService.registerCompany(user, companyRequest()))
                .isInstanceOf(ConflictException.class)
                .hasMessage("CNPJ already registered");
        verify(companyMembershipService, never()).assignOwner(any(), any(), any());
        verify(userRepository, never()).saveAndFlush(any());
        assertThat(user.getCompanyId()).isNull();
    }

    @Test
    void registerCompany_invalidCnpj_throwsUnprocessableWithoutCreatingAnything() {
        UserEntity user = UserEntity.builder().id(UUID.randomUUID()).build();
        CompanyRegistrationRequestDTO request = companyRequest();
        request.setCnpj("11.222.333/0001-00");

        assertThatThrownBy(() -> onboardingService.registerCompany(user, request))
                .isInstanceOf(UnprocessableEntityException.class);
        verify(companyRepository, never()).saveAndFlush(any());
    }

    @Test
    void registerCompany_userNoLongerExists_throwsNotFound() {
        UserEntity user = UserEntity.builder().id(UUID.randomUUID()).build();
        when(userRepository.findById(user.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> onboardingService.registerCompany(user, companyRequest()))
                .isInstanceOf(NotFoundException.class);
        verify(companyRepository, never()).saveAndFlush(any());
    }

    @Test
    void registerCompany_concurrentSameCnpj_throwsConflictForTheCnpj() {
        UserEntity user = UserEntity.builder().id(UUID.randomUUID()).build();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(companyRepository.saveAndFlush(any())).thenThrow(uniqueViolation("companies_cnpj_key"));

        assertThatThrownBy(() -> onboardingService.registerCompany(user, companyRequest()))
                .isInstanceOf(ConflictException.class)
                .hasMessage("CNPJ already registered");
        verify(companyMembershipService, never()).assignOwner(any(), any(), any());
    }

    @Test
    void registerCompany_concurrentSecondCompanyForSameUser_throwsConflictForTheMembership() {
        UserEntity user = UserEntity.builder().id(UUID.randomUUID()).build();
        stubCompanyAndUserPersistence(UUID.randomUUID(), user.getId());
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(companyMembershipService.assignOwner(any(), any(), any()))
                .thenThrow(uniqueViolation(OnboardingService.ACTIVE_MEMBERSHIP_UNIQUE_CONSTRAINT));

        assertThatThrownBy(() -> onboardingService.registerCompany(user, companyRequest()))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("USER_ALREADY_IN_COMPANY");
        verify(aveniaKycVerificationRepository, never()).save(any());
    }

    @Test
    void registerCompany_unexpectedMembershipIntegrityViolation_isNotReportedAsConflict() {
        UserEntity user = UserEntity.builder().id(UUID.randomUUID()).build();
        stubCompanyAndUserPersistence(UUID.randomUUID(), user.getId());
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        DataIntegrityViolationException failure = new DataIntegrityViolationException("no cause message");
        when(companyMembershipService.assignOwner(any(), any(), any())).thenThrow(failure);

        assertThatThrownBy(() -> onboardingService.registerCompany(user, companyRequest())).isSameAs(failure);
    }

    private static DataIntegrityViolationException uniqueViolation(String constraint) {
        return new DataIntegrityViolationException("could not execute statement",
                new RuntimeException("duplicate key value violates unique constraint \"" + constraint + "\""));
    }

    private AccountRequestDTO accountRequest() {
        AccountRequestDTO request = new AccountRequestDTO();
        request.setFullName("Joao da Silva");
        request.setEmail("joao@example.com");
        request.setPassword("Senha@123");
        request.setConfirmPassword("Senha@123");
        return request;
    }

    private CompanyRegistrationRequestDTO companyRequest() {
        CompanyRegistrationRequestDTO request = new CompanyRegistrationRequestDTO();
        request.setLegalName("Empresa Exemplo Ltda");
        request.setCnpj("11.222.333/0001-81");
        request.setCountry("Brasil");
        request.setZipCode("90000-000");
        request.setCity("Porto Alegre");
        request.setState("RS");
        return request;
    }

    private void stubCompanyAndUserPersistence(UUID companyId, UUID userId) {
        when(companyRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            CompanyEntity company = invocation.getArgument(0);
            company.setId(companyId);
            return company;
        });
        when(userRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(userId);
            return user;
        });
        when(aveniaKycVerificationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(passwordEncoder.encode("Senha@123")).thenReturn("hash-bcrypt");
        when(jwtTokenUtils.generateToken(any())).thenReturn("jwt-token-123");
    }

    private OnboardingRequestDTO validRequest() {
        OnboardingRequestDTO request = new OnboardingRequestDTO();
        request.setFullName("Joao da Silva");
        request.setEmail("joao@example.com");
        request.setPassword("Senha@123");
        request.setConfirmPassword("Senha@123");
        request.setLegalName("Empresa Exemplo Ltda");
        request.setCnpj("11.222.333/0001-81");
        request.setCountry("Brasil");
        request.setZipCode("90000-000");
        request.setCity("Porto Alegre");
        request.setState("RS");
        return request;
    }
}
