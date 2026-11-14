package ages.vstable.backend.service;

import ages.vstable.backend.configuration.TermsProperties;
import ages.vstable.backend.dto.membership.InvitationAcceptRequest;
import ages.vstable.backend.dto.membership.InvitationAcceptanceMode;
import ages.vstable.backend.dto.membership.InvitationNewUserAcceptRequest;
import ages.vstable.backend.dto.membership.InvitationNewUserAcceptResponse;
import ages.vstable.backend.dto.membership.InvitationResolveResponse;
import ages.vstable.backend.dto.membership.MemberResponse;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.CompanyInvitationEntity;
import ages.vstable.backend.entity.CompanyMembershipEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.InvitationStatus;
import ages.vstable.backend.entity.enums.MembershipStatus;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.ForbiddenException;
import ages.vstable.backend.exception.GoneException;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.repository.AdministratorRepository;
import ages.vstable.backend.repository.CompanyInvitationReplacedTokenRepository;
import ages.vstable.backend.repository.CompanyInvitationRepository;
import ages.vstable.backend.repository.CompanyMembershipRepository;
import ages.vstable.backend.repository.CompanyRepository;
import ages.vstable.backend.repository.UserRepository;
import ages.vstable.backend.utils.InvitationTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompanyInvitationAcceptanceServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-12T15:00:00Z");
    private static final String TERMS_VERSION = "v1";
    private static final String PASSWORD = "Senha@123";
    private static final String INVITED_EMAIL = "mariana@agrosul.com.br";

    @Mock
    private CompanyInvitationRepository invitationRepository;
    @Mock
    private CompanyInvitationReplacedTokenRepository replacedTokenRepository;
    @Mock
    private CompanyMembershipRepository membershipRepository;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AdministratorRepository administratorRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    private final InvitationTokens invitationTokens = new InvitationTokens();
    private final UUID companyId = UUID.randomUUID();
    private final UUID creatorId = UUID.randomUUID();
    private CompanyInvitationAcceptanceService service;
    private String token;
    private String tokenHash;

    @BeforeEach
    void setUp() {
        TermsProperties terms = new TermsProperties(TERMS_VERSION, "https://frontend.example/termos-de-uso",
                "https://frontend.example/politica-de-privacidade");
        service = new CompanyInvitationAcceptanceService(invitationRepository, replacedTokenRepository,
                membershipRepository, companyRepository, userRepository, administratorRepository, invitationTokens,
                passwordEncoder, terms, Clock.fixed(NOW, ZoneOffset.UTC));
        token = invitationTokens.generate();
        tokenHash = invitationTokens.hash(token);
    }

    // ---- consulta ----

    @Test
    void resolve_pendingInvitationWithoutAccount_returnsScreenDataWithRegisterModeAndChangesNothing() {
        // Arrange
        CompanyInvitationEntity invitation = givenInvitation(InvitationStatus.PENDING, false);
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company("AgroSul", "AgroSul Ltda")));
        when(userRepository.findById(creatorId)).thenReturn(Optional.of(user(creatorId, "João Rangel", "j@x.com")));
        givenNoIdentity();

        // Act
        InvitationResolveResponse response = service.resolve(token);

        // Assert
        assertThat(response.invitationId()).isEqualTo(invitation.getId());
        assertThat(response.company().id()).isEqualTo(companyId);
        assertThat(response.company().name()).isEqualTo("AgroSul");
        assertThat(response.invitedBy().name()).isEqualTo("João Rangel");
        assertThat(response.recipient().name()).isEqualTo("Mariana Costa");
        assertThat(response.recipient().email()).isEqualTo(INVITED_EMAIL);
        assertThat(response.role()).isEqualTo(CompanyRole.OPERATOR);
        assertThat(response.acceptanceMode()).isEqualTo(InvitationAcceptanceMode.REGISTER);
        assertThat(response.terms().version()).isEqualTo(TERMS_VERSION);
        assertThat(response.terms().termsUrl()).isEqualTo("https://frontend.example/termos-de-uso");
        assertThat(response.terms().privacyUrl()).isEqualTo("https://frontend.example/politica-de-privacidade");
        assertThat(response.toString()).doesNotContain(INVITED_EMAIL).doesNotContain("Mariana");
        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.PENDING);
        assertThat(invitation.getVersion()).isEqualTo(2);
        verify(invitationRepository, never()).saveAndFlush(any());
        verify(invitationRepository, never()).findByTokenHashForUpdate(anyString());
    }

    @Test
    void resolve_existingCompanyAccountWithAnotherCase_returnsLoginMode() {
        // Arrange
        givenInvitation(InvitationStatus.PENDING, false);
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company(null, "AgroSul Ltda")));
        when(userRepository.findById(creatorId)).thenReturn(Optional.of(user(creatorId, "João", "j@x.com")));
        when(userRepository.findFirstByEmailIgnoreCase(INVITED_EMAIL))
                .thenReturn(Optional.of(user(UUID.randomUUID(), "Mari", "Mariana@AgroSul.com.br")));

        // Act
        InvitationResolveResponse response = service.resolve(token);

        // Assert
        assertThat(response.acceptanceMode()).isEqualTo(InvitationAcceptanceMode.LOGIN);
        assertThat(response.company().name()).isEqualTo("AgroSul Ltda");
    }

    @Test
    void resolve_administratorEmail_returnsLoginModeSoNoSecondIdentityIsCreated() {
        // Arrange
        givenInvitation(InvitationStatus.PENDING, false);
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company(" ", "AgroSul Ltda")));
        when(userRepository.findById(creatorId)).thenReturn(Optional.empty());
        when(userRepository.findFirstByEmailIgnoreCase(INVITED_EMAIL)).thenReturn(Optional.empty());
        when(administratorRepository.existsByEmailIgnoreCase(INVITED_EMAIL)).thenReturn(true);

        // Act
        InvitationResolveResponse response = service.resolve(token);

        // Assert
        assertThat(response.acceptanceMode()).isEqualTo(InvitationAcceptanceMode.LOGIN);
        assertThat(response.company().name()).isEqualTo("AgroSul Ltda");
        assertThat(response.invitedBy().name()).isNull();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "curto", "token com espaços que não é base64url válido de 43 c",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void resolve_malformedToken_isInvalidWithoutQueryingTheDatabase(String malformed) {
        // Act / Assert
        assertCode(() -> service.resolve(malformed), NotFoundException.class, "INVITATION_INVALID");
        verifyNoInteractions(invitationRepository, replacedTokenRepository);
    }

    @Test
    void resolve_unknownToken_isInvalid() {
        // Arrange
        when(invitationRepository.findByTokenHash(tokenHash)).thenReturn(Optional.empty());
        when(replacedTokenRepository.existsByTokenHash(tokenHash)).thenReturn(false);

        // Act / Assert
        assertCode(() -> service.resolve(token), NotFoundException.class, "INVITATION_INVALID");
    }

    @Test
    void resolve_tokenReplacedByRegeneration_isSuperseded() {
        // Arrange
        when(invitationRepository.findByTokenHash(tokenHash)).thenReturn(Optional.empty());
        when(replacedTokenRepository.existsByTokenHash(tokenHash)).thenReturn(true);

        // Act / Assert
        assertCode(() -> service.resolve(token), GoneException.class, "INVITATION_SUPERSEDED");
    }

    @Test
    void resolve_revokedInvitation_isRevoked() {
        // Arrange
        givenInvitation(InvitationStatus.REVOKED, false);

        // Act / Assert
        assertCode(() -> service.resolve(token), GoneException.class, "INVITATION_REVOKED");
    }

    @Test
    void resolve_acceptedInvitation_isAlreadyUsed() {
        // Arrange
        givenInvitation(InvitationStatus.ACCEPTED, false);

        // Act / Assert
        assertCode(() -> service.resolve(token), ConflictException.class, "INVITATION_ALREADY_USED");
    }

    // ---- aceite por usuário novo ----

    @Test
    void acceptNewUser_validRequest_createsAccountMembershipAndConsumesTheInvitationWithoutJwt() {
        // Arrange
        CompanyInvitationEntity invitation = givenInvitation(InvitationStatus.PENDING, true);
        givenNoIdentity();
        when(passwordEncoder.encode(PASSWORD)).thenReturn("bcrypt-hash");
        UUID newUserId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        when(userRepository.saveAndFlush(any(UserEntity.class))).thenAnswer(call -> {
            UserEntity saved = call.getArgument(0);
            saved.setId(newUserId);
            return saved;
        });
        when(membershipRepository.saveAndFlush(any(CompanyMembershipEntity.class))).thenAnswer(call -> {
            CompanyMembershipEntity saved = call.getArgument(0);
            saved.setId(membershipId);
            return saved;
        });

        // Act
        InvitationNewUserAcceptResponse response = service.acceptNewUser(newUserRequest(PASSWORD, PASSWORD, true));

        // Assert: conta com os dados do convite
        ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).saveAndFlush(userCaptor.capture());
        UserEntity user = userCaptor.getValue();
        assertThat(user.getFullName()).isEqualTo("Mariana Costa");
        assertThat(user.getEmail()).isEqualTo(INVITED_EMAIL);
        assertThat(user.getPasswordHash()).isEqualTo("bcrypt-hash");
        assertThat(user.getPasswordSalt()).isNotBlank();
        assertThat(user.getTwoFactorEnabled()).isFalse();
        assertThat(user.getTwoFactorSecret()).isNull();
        assertThat(user.getCompanyId()).isEqualTo(companyId);
        // vínculo com o perfil do convite
        ArgumentCaptor<CompanyMembershipEntity> membershipCaptor = ArgumentCaptor.forClass(CompanyMembershipEntity.class);
        verify(membershipRepository).saveAndFlush(membershipCaptor.capture());
        CompanyMembershipEntity membership = membershipCaptor.getValue();
        assertThat(membership.getCompanyId()).isEqualTo(companyId);
        assertThat(membership.getUserId()).isEqualTo(newUserId);
        assertThat(membership.getRole()).isEqualTo(CompanyRole.OPERATOR);
        assertThat(membership.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
        // convite consumido, com os termos registrados
        assertAccepted(invitation, newUserId, membershipId);
        // resposta sem JWT
        assertThat(response.userId()).isEqualTo(newUserId);
        assertThat(response.nextAction()).isEqualTo("LOGIN");
        assertThat(response.membership().id()).isEqualTo(membershipId);
        assertThat(response.membership().name()).isEqualTo("Mariana Costa");
        assertThat(response.membership().role()).isEqualTo(CompanyRole.OPERATOR);
        assertThat(response.membership().status()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(response.membership().version()).isEqualTo(1);
        verify(invitationRepository, never()).findByTokenHash(anyString());
    }

    @Test
    void acceptNewUser_emailAlreadyHasAnAccount_isUserAlreadyExistsAndTouchesNothing() {
        // Arrange
        CompanyInvitationEntity invitation = givenInvitation(InvitationStatus.PENDING, true);
        when(userRepository.findFirstByEmailIgnoreCase(INVITED_EMAIL))
                .thenReturn(Optional.of(user(UUID.randomUUID(), "Mari", INVITED_EMAIL)));

        // Act / Assert
        assertCode(() -> service.acceptNewUser(newUserRequest(PASSWORD, PASSWORD, true)),
                ConflictException.class, "USER_ALREADY_EXISTS");
        assertNothingWritten(invitation);
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void acceptNewUser_passwordConfirmationDiffers_isConfirmationMismatch() {
        // Arrange
        CompanyInvitationEntity invitation = givenInvitation(InvitationStatus.PENDING, true);
        givenNoIdentity();

        // Act / Assert
        assertCode(() -> service.acceptNewUser(newUserRequest(PASSWORD, "Senha@124", true)),
                UnprocessableEntityException.class, "PASSWORD_CONFIRMATION_MISMATCH");
        assertNothingWritten(invitation);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Ab@1", "SenhaSemNumero@", "SenhaSemEspecial1", "Senha@123\nlinha"})
    void acceptNewUser_passwordOutsideThePolicy_isTooWeak(String weak) {
        // Arrange
        CompanyInvitationEntity invitation = givenInvitation(InvitationStatus.PENDING, true);
        givenNoIdentity();

        // Act / Assert
        assertCode(() -> service.acceptNewUser(newUserRequest(weak, weak, true)),
                UnprocessableEntityException.class, "PASSWORD_TOO_WEAK");
        assertNothingWritten(invitation);
    }

    @Test
    void acceptNewUser_passwordAbove72Utf8Bytes_isTooWeak() {
        // Arrange
        CompanyInvitationEntity invitation = givenInvitation(InvitationStatus.PENDING, true);
        givenNoIdentity();
        String tooLong = "1@" + "é".repeat(36);

        // Act / Assert
        assertCode(() -> service.acceptNewUser(newUserRequest(tooLong, tooLong, true)),
                UnprocessableEntityException.class, "PASSWORD_TOO_WEAK");
        assertNothingWritten(invitation);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(booleans = false)
    void acceptNewUser_termsNotAccepted_isTermsNotAccepted(Boolean termsAccepted) {
        // Arrange
        CompanyInvitationEntity invitation = givenInvitation(InvitationStatus.PENDING, true);
        givenNoIdentity();

        // Act / Assert
        assertCode(() -> service.acceptNewUser(newUserRequest(PASSWORD, PASSWORD, termsAccepted)),
                UnprocessableEntityException.class, "TERMS_NOT_ACCEPTED");
        assertNothingWritten(invitation);
    }

    @Test
    void acceptNewUser_outdatedTermsVersion_isTermsVersionMismatch() {
        // Arrange
        CompanyInvitationEntity invitation = givenInvitation(InvitationStatus.PENDING, true);
        givenNoIdentity();
        InvitationNewUserAcceptRequest request = new InvitationNewUserAcceptRequest(token, PASSWORD, PASSWORD, true, "v0");

        // Act / Assert
        assertCode(() -> service.acceptNewUser(request), ConflictException.class, "TERMS_VERSION_MISMATCH");
        assertNothingWritten(invitation);
    }

    @Test
    void acceptNewUser_invitationAlreadyAccepted_isAlreadyUsedSoALostResponseIsNotRetriedSilently() {
        // Arrange
        givenInvitation(InvitationStatus.ACCEPTED, true);

        // Act / Assert
        assertCode(() -> service.acceptNewUser(newUserRequest(PASSWORD, PASSWORD, true)),
                ConflictException.class, "INVITATION_ALREADY_USED");
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void acceptNewUser_revokedInvitation_isRevoked() {
        // Arrange
        givenInvitation(InvitationStatus.REVOKED, true);

        // Act / Assert
        assertCode(() -> service.acceptNewUser(newUserRequest(PASSWORD, PASSWORD, true)),
                GoneException.class, "INVITATION_REVOKED");
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void acceptNewUser_supersededToken_isSuperseded() {
        // Arrange
        when(invitationRepository.findByTokenHashForUpdate(tokenHash)).thenReturn(Optional.empty());
        when(replacedTokenRepository.existsByTokenHash(tokenHash)).thenReturn(true);

        // Act / Assert
        assertCode(() -> service.acceptNewUser(newUserRequest(PASSWORD, PASSWORD, true)),
                GoneException.class, "INVITATION_SUPERSEDED");
        verify(userRepository, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"users_email_key", "users_email_lower_key"})
    void acceptNewUser_concurrentSignupWithTheSameEmailWinsTheRace_isUserAlreadyExists(String constraint) {
        // Arrange: a outra conta pode ter o mesmo e-mail com outras maiúsculas (índice em lower(email)).
        CompanyInvitationEntity invitation = givenInvitation(InvitationStatus.PENDING, true);
        givenNoIdentity();
        when(passwordEncoder.encode(PASSWORD)).thenReturn("bcrypt-hash");
        when(userRepository.saveAndFlush(any(UserEntity.class))).thenThrow(uniqueViolation(constraint));

        // Act / Assert
        assertCode(() -> service.acceptNewUser(newUserRequest(PASSWORD, PASSWORD, true)),
                ConflictException.class, "USER_ALREADY_EXISTS");
        verify(membershipRepository, never()).saveAndFlush(any());
        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.PENDING);
    }

    @Test
    void acceptNewUser_unexpectedIntegrityFailure_isPropagated() {
        // Arrange
        givenInvitation(InvitationStatus.PENDING, true);
        givenNoIdentity();
        when(passwordEncoder.encode(PASSWORD)).thenReturn("bcrypt-hash");
        DataIntegrityViolationException failure = uniqueViolation("some_other_constraint");
        when(userRepository.saveAndFlush(any(UserEntity.class))).thenThrow(failure);

        // Act / Assert
        assertThatThrownBy(() -> service.acceptNewUser(newUserRequest(PASSWORD, PASSWORD, true))).isSameAs(failure);
    }

    // ---- aceite por usuário existente ----

    @Test
    void accept_recipientWithoutCompany_createsMembershipConsumesInvitationAndPreservesTheAccount() {
        // Arrange
        CompanyInvitationEntity invitation = givenInvitation(InvitationStatus.PENDING, true);
        UserEntity stored = user(UUID.randomUUID(), "Mariana C. (nome atual)", "Mariana@AgroSul.com.br");
        stored.setPasswordHash("hash-original");
        stored.setTwoFactorEnabled(true);
        stored.setTwoFactorSecret("segredo-2fa");
        givenStoredUser(stored);
        when(membershipRepository.findFirstByUserIdAndStatusOrderByRemovedAtDesc(stored.getId(), MembershipStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(membershipRepository.existsByUserId(stored.getId())).thenReturn(false);
        UUID membershipId = UUID.randomUUID();
        when(membershipRepository.saveAndFlush(any(CompanyMembershipEntity.class))).thenAnswer(call -> {
            CompanyMembershipEntity saved = call.getArgument(0);
            saved.setId(membershipId);
            return saved;
        });

        // Act
        MemberResponse member = service.accept(principalOf(stored), acceptRequest(true, TERMS_VERSION));

        // Assert
        assertThat(member.id()).isEqualTo(membershipId);
        assertThat(member.userId()).isEqualTo(stored.getId());
        assertThat(member.companyId()).isEqualTo(companyId);
        assertThat(member.name()).isEqualTo("Mariana C. (nome atual)");
        assertThat(member.role()).isEqualTo(CompanyRole.OPERATOR);
        assertThat(member.status()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(stored.getPasswordHash()).isEqualTo("hash-original");
        assertThat(stored.getTwoFactorEnabled()).isTrue();
        assertThat(stored.getTwoFactorSecret()).isEqualTo("segredo-2fa");
        assertThat(stored.getFullName()).isEqualTo("Mariana C. (nome atual)");
        assertThat(stored.getEmail()).isEqualTo("Mariana@AgroSul.com.br");
        assertThat(stored.getCompanyId()).isEqualTo(companyId);
        assertAccepted(invitation, stored.getId(), membershipId);
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void accept_removedMemberOfAnotherCompany_canJoinTheInvitingCompany() {
        // Arrange
        CompanyInvitationEntity invitation = givenInvitation(InvitationStatus.PENDING, true);
        UserEntity stored = user(UUID.randomUUID(), "Mariana", INVITED_EMAIL);
        stored.setCompanyId(UUID.randomUUID());
        givenStoredUser(stored);
        when(membershipRepository.findFirstByUserIdAndStatusOrderByRemovedAtDesc(stored.getId(), MembershipStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(membershipRepository.existsByUserId(stored.getId())).thenReturn(true);
        when(membershipRepository.saveAndFlush(any(CompanyMembershipEntity.class))).thenAnswer(call -> call.getArgument(0));

        // Act
        service.accept(principalOf(stored), acceptRequest(true, TERMS_VERSION));

        // Assert
        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
        assertThat(stored.getCompanyId()).isEqualTo(companyId);
    }

    @Test
    void accept_authenticatedEmailDiffersFromTheInvitation_isIdentityMismatchAndTouchesNothing() {
        // Arrange
        CompanyInvitationEntity invitation = givenInvitation(InvitationStatus.PENDING, true);
        UserEntity intruder = user(UUID.randomUUID(), "Outra Pessoa", "outra@agrosul.com.br");
        givenStoredUser(intruder);

        // Act / Assert
        assertCode(() -> service.accept(principalOf(intruder), acceptRequest(true, TERMS_VERSION)),
                ForbiddenException.class, "INVITATION_IDENTITY_MISMATCH");
        assertNothingWritten(invitation);
    }

    @Test
    void accept_principalNoLongerExists_isIdentityMismatch() {
        // Arrange
        CompanyInvitationEntity invitation = givenInvitation(InvitationStatus.PENDING, true);
        UUID missingId = UUID.randomUUID();
        when(userRepository.findById(missingId)).thenReturn(Optional.empty());

        // Act / Assert
        assertCode(() -> service.accept(user(missingId, "X", INVITED_EMAIL), acceptRequest(true, TERMS_VERSION)),
                ForbiddenException.class, "INVITATION_IDENTITY_MISMATCH");
        assertNothingWritten(invitation);
    }

    @Test
    void accept_activeMemberOfAnotherCompany_isUserInOtherCompanyWithoutReassociation() {
        // Arrange
        CompanyInvitationEntity invitation = givenInvitation(InvitationStatus.PENDING, true);
        UserEntity stored = user(UUID.randomUUID(), "Mariana", INVITED_EMAIL);
        givenStoredUser(stored);
        when(membershipRepository.findFirstByUserIdAndStatusOrderByRemovedAtDesc(stored.getId(), MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(membership(stored.getId(), UUID.randomUUID(), MembershipStatus.ACTIVE)));

        // Act / Assert
        assertCode(() -> service.accept(principalOf(stored), acceptRequest(true, TERMS_VERSION)),
                ConflictException.class, "USER_IN_OTHER_COMPANY");
        assertNothingWritten(invitation);
    }

    @Test
    void accept_activeMemberOfTheSameCompany_isMemberAlreadyExists() {
        // Arrange
        CompanyInvitationEntity invitation = givenInvitation(InvitationStatus.PENDING, true);
        UserEntity stored = user(UUID.randomUUID(), "Mariana", INVITED_EMAIL);
        givenStoredUser(stored);
        when(membershipRepository.findFirstByUserIdAndStatusOrderByRemovedAtDesc(stored.getId(), MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(membership(stored.getId(), companyId, MembershipStatus.ACTIVE)));

        // Act / Assert
        assertCode(() -> service.accept(principalOf(stored), acceptRequest(true, TERMS_VERSION)),
                ConflictException.class, "MEMBER_ALREADY_EXISTS");
        assertNothingWritten(invitation);
    }

    @Test
    void accept_legacyAccountWithCompanyAndNoMembership_isUserInOtherCompany() {
        // Arrange
        CompanyInvitationEntity invitation = givenInvitation(InvitationStatus.PENDING, true);
        UserEntity stored = user(UUID.randomUUID(), "Mariana", INVITED_EMAIL);
        stored.setCompanyId(UUID.randomUUID());
        givenStoredUser(stored);
        when(membershipRepository.findFirstByUserIdAndStatusOrderByRemovedAtDesc(stored.getId(), MembershipStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(membershipRepository.existsByUserId(stored.getId())).thenReturn(false);

        // Act / Assert
        assertCode(() -> service.accept(principalOf(stored), acceptRequest(true, TERMS_VERSION)),
                ConflictException.class, "USER_IN_OTHER_COMPANY");
        assertNothingWritten(invitation);
    }

    @Test
    void accept_concurrentMembershipCreatedFirst_isUserInOtherCompany() {
        // Arrange
        givenInvitation(InvitationStatus.PENDING, true);
        UserEntity stored = user(UUID.randomUUID(), "Mariana", INVITED_EMAIL);
        givenStoredUser(stored);
        when(membershipRepository.findFirstByUserIdAndStatusOrderByRemovedAtDesc(stored.getId(), MembershipStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(membershipRepository.existsByUserId(stored.getId())).thenReturn(false);
        when(membershipRepository.saveAndFlush(any(CompanyMembershipEntity.class)))
                .thenThrow(uniqueViolation("company_memberships_active_user_key"));

        // Act / Assert
        assertCode(() -> service.accept(principalOf(stored), acceptRequest(true, TERMS_VERSION)),
                ConflictException.class, "USER_IN_OTHER_COMPANY");
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void accept_sameUserRepeatsWhileTheMembershipIsActive_returnsTheSameMembershipWithoutSideEffects() {
        // Arrange
        UserEntity stored = user(UUID.randomUUID(), "Mariana", INVITED_EMAIL);
        CompanyMembershipEntity existing = membership(stored.getId(), companyId, MembershipStatus.ACTIVE);
        CompanyInvitationEntity invitation = givenInvitation(InvitationStatus.ACCEPTED, true);
        invitation.setAcceptedBy(stored.getId());
        invitation.setMembershipId(existing.getId());
        long version = invitation.getVersion();
        givenStoredUser(stored);
        when(membershipRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        // Act
        MemberResponse member = service.accept(principalOf(stored), acceptRequest(true, TERMS_VERSION));

        // Assert
        assertThat(member.id()).isEqualTo(existing.getId());
        assertThat(member.status()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(invitation.getVersion()).isEqualTo(version);
        verify(membershipRepository, never()).saveAndFlush(any());
        verify(invitationRepository, never()).saveAndFlush(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void accept_repeatAfterTheMembershipWasRemoved_isAlreadyUsedAndNeverReactivatesAccess() {
        // Arrange
        UserEntity stored = user(UUID.randomUUID(), "Mariana", INVITED_EMAIL);
        CompanyMembershipEntity removed = membership(stored.getId(), companyId, MembershipStatus.REMOVED);
        CompanyInvitationEntity invitation = givenInvitation(InvitationStatus.ACCEPTED, true);
        invitation.setAcceptedBy(stored.getId());
        invitation.setMembershipId(removed.getId());
        givenStoredUser(stored);
        when(membershipRepository.findById(removed.getId())).thenReturn(Optional.of(removed));

        // Act / Assert
        assertCode(() -> service.accept(principalOf(stored), acceptRequest(true, TERMS_VERSION)),
                ConflictException.class, "INVITATION_ALREADY_USED");
        assertThat(removed.getStatus()).isEqualTo(MembershipStatus.REMOVED);
        verify(membershipRepository, never()).saveAndFlush(any());
    }

    @Test
    void accept_invitationAcceptedByAnotherAccount_isAlreadyUsed() {
        // Arrange
        UserEntity stored = user(UUID.randomUUID(), "Mariana", INVITED_EMAIL);
        CompanyInvitationEntity invitation = givenInvitation(InvitationStatus.ACCEPTED, true);
        invitation.setAcceptedBy(UUID.randomUUID());
        invitation.setMembershipId(UUID.randomUUID());
        givenStoredUser(stored);

        // Act / Assert
        assertCode(() -> service.accept(principalOf(stored), acceptRequest(true, TERMS_VERSION)),
                ConflictException.class, "INVITATION_ALREADY_USED");
        verify(membershipRepository, never()).findById(any());
    }

    @Test
    void accept_acceptedInvitationWithoutRecordedMembership_isAlreadyUsed() {
        // Arrange
        UserEntity stored = user(UUID.randomUUID(), "Mariana", INVITED_EMAIL);
        givenInvitation(InvitationStatus.ACCEPTED, true);
        givenStoredUser(stored);

        // Act / Assert
        assertCode(() -> service.accept(principalOf(stored), acceptRequest(true, TERMS_VERSION)),
                ConflictException.class, "INVITATION_ALREADY_USED");
    }

    @Test
    void accept_revokedInvitation_isRevokedBeforeAnyIdentityCheck() {
        // Arrange
        givenInvitation(InvitationStatus.REVOKED, true);

        // Act / Assert
        assertCode(() -> service.accept(user(UUID.randomUUID(), "X", INVITED_EMAIL), acceptRequest(true, TERMS_VERSION)),
                GoneException.class, "INVITATION_REVOKED");
        verifyNoInteractions(userRepository);
    }

    @Test
    void accept_unknownToken_isInvalid() {
        // Arrange
        when(invitationRepository.findByTokenHashForUpdate(tokenHash)).thenReturn(Optional.empty());
        when(replacedTokenRepository.existsByTokenHash(tokenHash)).thenReturn(false);

        // Act / Assert
        assertCode(() -> service.accept(user(UUID.randomUUID(), "X", INVITED_EMAIL), acceptRequest(true, TERMS_VERSION)),
                NotFoundException.class, "INVITATION_INVALID");
    }

    @Test
    void accept_termsNotAccepted_isTermsNotAccepted() {
        // Arrange
        CompanyInvitationEntity invitation = givenInvitation(InvitationStatus.PENDING, true);
        UserEntity stored = user(UUID.randomUUID(), "Mariana", INVITED_EMAIL);
        givenStoredUser(stored);

        // Act / Assert
        assertCode(() -> service.accept(principalOf(stored), acceptRequest(false, TERMS_VERSION)),
                UnprocessableEntityException.class, "TERMS_NOT_ACCEPTED");
        assertNothingWritten(invitation);
    }

    @Test
    void accept_outdatedTermsVersion_isTermsVersionMismatch() {
        // Arrange
        CompanyInvitationEntity invitation = givenInvitation(InvitationStatus.PENDING, true);
        UserEntity stored = user(UUID.randomUUID(), "Mariana", INVITED_EMAIL);
        givenStoredUser(stored);

        // Act / Assert
        assertCode(() -> service.accept(principalOf(stored), acceptRequest(true, "v0")),
                ConflictException.class, "TERMS_VERSION_MISMATCH");
        assertNothingWritten(invitation);
    }

    // ---- apoio ----

    /** Convite com o token do teste, encontrado pela consulta com ou sem trava. */
    private CompanyInvitationEntity givenInvitation(InvitationStatus status, boolean locked) {
        boolean pending = status == InvitationStatus.PENDING;
        OffsetDateTime created = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusDays(3);
        CompanyInvitationEntity invitation = CompanyInvitationEntity.builder()
                .id(UUID.randomUUID())
                .companyId(companyId)
                .name("Mariana Costa")
                .email(INVITED_EMAIL)
                .role(CompanyRole.OPERATOR)
                .status(status)
                .version(2)
                .tokenHash(tokenHash)
                .tokenCiphertext(pending ? "cifrado" : null)
                .tokenKeyVersion(pending ? "v1" : null)
                .createdBy(creatorId)
                .createdAt(created)
                .updatedAt(created)
                .build();
        if (locked) {
            when(invitationRepository.findByTokenHashForUpdate(tokenHash)).thenReturn(Optional.of(invitation));
        } else {
            when(invitationRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(invitation));
        }
        return invitation;
    }

    private void givenNoIdentity() {
        when(userRepository.findFirstByEmailIgnoreCase(INVITED_EMAIL)).thenReturn(Optional.empty());
        lenient().when(administratorRepository.existsByEmailIgnoreCase(INVITED_EMAIL)).thenReturn(false);
    }

    private void givenStoredUser(UserEntity stored) {
        when(userRepository.findById(stored.getId())).thenReturn(Optional.of(stored));
    }

    /** O principal do filtro JWT é uma cópia desligada da conta; o serviço relê a conta do banco. */
    private static UserEntity principalOf(UserEntity stored) {
        return user(stored.getId(), stored.getFullName(), stored.getEmail());
    }

    private InvitationNewUserAcceptRequest newUserRequest(String password, String confirmation, Boolean termsAccepted) {
        return new InvitationNewUserAcceptRequest(token, password, confirmation, termsAccepted, TERMS_VERSION);
    }

    private InvitationAcceptRequest acceptRequest(Boolean termsAccepted, String termsVersion) {
        return new InvitationAcceptRequest(token, termsAccepted, termsVersion);
    }

    private void assertAccepted(CompanyInvitationEntity invitation, UUID userId, UUID membershipId) {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
        assertThat(invitation.getAcceptedAt()).isEqualTo(now);
        assertThat(invitation.getAcceptedBy()).isEqualTo(userId);
        assertThat(invitation.getMembershipId()).isEqualTo(membershipId);
        assertThat(invitation.getTermsVersion()).isEqualTo(TERMS_VERSION);
        assertThat(invitation.getTermsAcceptedAt()).isEqualTo(now);
        assertThat(invitation.getTokenCiphertext()).isNull();
        assertThat(invitation.getTokenKeyVersion()).isNull();
        assertThat(invitation.getTokenHash()).isEqualTo(tokenHash);
        assertThat(invitation.getVersion()).isEqualTo(3);
        verify(invitationRepository).saveAndFlush(invitation);
    }

    private void assertNothingWritten(CompanyInvitationEntity invitation) {
        assertThat(invitation.getStatus()).isNotEqualTo(InvitationStatus.ACCEPTED);
        verify(userRepository, never()).saveAndFlush(any());
        verify(membershipRepository, never()).saveAndFlush(any());
        verify(invitationRepository, never()).saveAndFlush(any());
    }

    private static <T extends RuntimeException> void assertCode(Runnable action, Class<T> type, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(type)
                .extracting("code")
                .isEqualTo(code);
    }

    private static DataIntegrityViolationException uniqueViolation(String constraint) {
        return new DataIntegrityViolationException("could not execute statement",
                new RuntimeException("duplicate key value violates unique constraint \"" + constraint + "\""));
    }

    private CompanyEntity company(String tradeName, String legalName) {
        CompanyEntity company = new CompanyEntity();
        company.setId(companyId);
        company.setTradeName(tradeName);
        company.setLegalName(legalName);
        return company;
    }

    private static UserEntity user(UUID id, String name, String email) {
        return UserEntity.builder().id(id).fullName(name).email(email).passwordHash("h").passwordSalt("s").build();
    }

    private static CompanyMembershipEntity membership(UUID userId, UUID companyId, MembershipStatus status) {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        return CompanyMembershipEntity.builder()
                .id(UUID.randomUUID())
                .companyId(companyId)
                .userId(userId)
                .role(CompanyRole.OPERATOR)
                .status(status)
                .joinedAt(now.minusDays(1))
                .removedAt(status == MembershipStatus.REMOVED ? now : null)
                .updatedAt(now)
                .build();
    }
}
