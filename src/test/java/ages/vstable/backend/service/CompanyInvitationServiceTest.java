package ages.vstable.backend.service;

import ages.vstable.backend.dto.membership.GeneratedInvitationResponse;
import ages.vstable.backend.dto.membership.InvitationCreateRequest;
import ages.vstable.backend.dto.membership.InvitationResponse;
import ages.vstable.backend.dto.membership.InviteRole;
import ages.vstable.backend.entity.CompanyInvitationEntity;
import ages.vstable.backend.entity.CompanyInvitationReplacedTokenEntity;
import ages.vstable.backend.entity.CompanyMembershipEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.InvitationStatus;
import ages.vstable.backend.entity.enums.MembershipStatus;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.GoneException;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.exception.PreconditionFailedException;
import ages.vstable.backend.exception.TooManyRequestsException;
import ages.vstable.backend.repository.AdministratorRepository;
import ages.vstable.backend.repository.CompanyInvitationReplacedTokenRepository;
import ages.vstable.backend.repository.CompanyInvitationRepository;
import ages.vstable.backend.repository.CompanyMembershipRepository;
import ages.vstable.backend.repository.UserRepository;
import ages.vstable.backend.utils.InvitationTokenCipher;
import ages.vstable.backend.utils.InvitationTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompanyInvitationServiceTest {

    private static final String KEY = "dGVzdC1vbmx5LWludml0ZS1rZXktMzItYnl0ZXMhISE=";
    private static final String TOKEN = "kX9fQ-opaque_token-0123456789abcdefghijklmnopqrs";
    private static final String ORIGIN = "https://frontend.example";
    private static final String LINK_PREFIX = ORIGIN + "/convites/empresa#token=";
    private static final Instant NOW = Instant.parse("2026-11-11T15:00:00Z");

    private final UUID companyId = UUID.randomUUID();
    private final OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private final InvitationTokenCipher cipher = new InvitationTokenCipher(KEY, "v1");
    private final InvitationTokens tokens = new InvitationTokens();

    @Mock
    private CompanyInvitationRepository invitationRepository;

    @Mock
    private CompanyInvitationReplacedTokenRepository replacedTokenRepository;

    @Mock
    private CompanyMembershipRepository membershipRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AdministratorRepository administratorRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    private CompanyInvitationService service;
    private UserEntity owner;

    @BeforeEach
    void setUp() {
        service = newService(ORIGIN);
        owner = UserEntity.builder().id(UUID.randomUUID()).companyId(companyId)
                .fullName("João Rangel").email("joao@agrosul.com.br").build();
    }

    private CompanyInvitationService newService(String origin) {
        return new CompanyInvitationService(invitationRepository, replacedTokenRepository, membershipRepository,
                userRepository, administratorRepository, cipher, tokens, transactionManager,
                Clock.fixed(NOW, ZoneOffset.UTC), origin);
    }

    // ---- consulta (#72) ----

    @Test
    void findLink_pendingInvitation_returnsTheLinkBuiltFromTheStoredToken() {
        // Arrange
        CompanyInvitationEntity invitation = invitation(InvitationStatus.PENDING, 3);
        found(invitation);

        // Act
        GeneratedInvitationResponse generated = service.findLink(companyId, invitation.getId());

        // Assert
        assertThat(generated.inviteUrl()).isEqualTo(LINK_PREFIX + TOKEN);
        assertThat(generated.invitation().id()).isEqualTo(invitation.getId());
        assertThat(generated.invitation().status()).isEqualTo(InvitationStatus.PENDING);
        assertThat(generated.invitation().version()).isEqualTo(3);
        assertThat(generated.invitation().createdBy().name()).isEqualTo("João Rangel");
    }

    @Test
    void findLink_isAReadOnlyOperation_neverChangesTheInvitation() {
        // Arrange
        CompanyInvitationEntity invitation = invitation(InvitationStatus.PENDING, 1);
        found(invitation);

        // Act
        service.findLink(companyId, invitation.getId());
        service.findLink(companyId, invitation.getId());

        // Assert
        verify(invitationRepository, never()).save(any());
        verify(invitationRepository, never()).saveAndFlush(any());
        verifyNoInteractions(replacedTokenRepository);
        assertThat(invitation.getVersion()).isEqualTo(1);
        assertThat(invitation.getTokenCiphertext()).isNotNull();
    }

    @Test
    void findLink_trimsTheTrailingSlashOfTheConfiguredOrigin() {
        // Arrange
        CompanyInvitationService slashService = newService(ORIGIN + "/");
        CompanyInvitationEntity invitation = invitation(InvitationStatus.PENDING, 1);
        found(invitation);

        // Act
        GeneratedInvitationResponse generated = slashService.findLink(companyId, invitation.getId());

        // Assert
        assertThat(generated.inviteUrl()).isEqualTo(LINK_PREFIX + TOKEN);
    }

    @Test
    void findLink_acceptedInvitation_isConflict() {
        // Arrange
        CompanyInvitationEntity invitation = invitation(InvitationStatus.ACCEPTED, 2);
        found(invitation);

        // Act / Assert
        assertThatThrownBy(() -> service.findLink(companyId, invitation.getId()))
                .isInstanceOfSatisfying(ConflictException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("INVITATION_ALREADY_USED"));
    }

    @Test
    void findLink_revokedInvitation_isGone() {
        // Arrange
        CompanyInvitationEntity invitation = invitation(InvitationStatus.REVOKED, 2);
        found(invitation);

        // Act / Assert
        assertThatThrownBy(() -> service.findLink(companyId, invitation.getId()))
                .isInstanceOfSatisfying(GoneException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("INVITATION_REVOKED"));
    }

    @Test
    void findLink_unknownInvitationOrOneFromAnotherCompany_isNotFound() {
        // Arrange
        UUID invitationId = UUID.randomUUID();
        when(invitationRepository.findByIdAndCompanyId(invitationId, companyId)).thenReturn(Optional.empty());

        // Act / Assert
        assertThatThrownBy(() -> service.findLink(companyId, invitationId))
                .isInstanceOfSatisfying(NotFoundException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("INVITATION_NOT_FOUND"));
        assertThatThrownBy(() -> service.find(companyId, invitationId))
                .isInstanceOfSatisfying(NotFoundException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("INVITATION_NOT_FOUND"));
    }

    @Test
    void find_returnsTheMetadataInAnyStatusWithoutSecrets() {
        for (InvitationStatus status : InvitationStatus.values()) {
            // Arrange
            CompanyInvitationEntity invitation = invitation(status, 4);
            found(invitation);

            // Act
            InvitationResponse response = service.find(companyId, invitation.getId());

            // Assert
            assertThat(response.status()).isEqualTo(status);
            assertThat(response.version()).isEqualTo(4);
            assertThat(response.createdAt().getOffset()).isEqualTo(ZoneOffset.UTC);
            assertThat(response.toString()).doesNotContain(TOKEN).doesNotContain(invitation.getTokenHash());
        }
    }

    // ---- criação (#73) ----

    @Test
    void create_newEmail_storesAPendingInvitationWithTheHashAndTheEncryptedCopyAndReturnsTheLink() {
        // Arrange
        noConflicts();
        savedInvitationsGetAnId();
        InvitationCreateRequest request =
                new InvitationCreateRequest("  Mariana Costa ", " Mariana@AgroSul.com.br ", InviteRole.OPERATOR);

        // Act
        GeneratedInvitationResponse generated = service.create(companyId, owner, request);

        // Assert
        CompanyInvitationEntity saved = savedInvitation();
        assertThat(saved.getCompanyId()).isEqualTo(companyId);
        assertThat(saved.getName()).isEqualTo("Mariana Costa");
        assertThat(saved.getEmail()).isEqualTo("mariana@agrosul.com.br");
        assertThat(saved.getRole()).isEqualTo(CompanyRole.OPERATOR);
        assertThat(saved.getStatus()).isEqualTo(InvitationStatus.PENDING);
        assertThat(saved.getVersion()).isEqualTo(1);
        assertThat(saved.getCreatedBy()).isEqualTo(owner.getId());
        assertThat(saved.getCreatedAt()).isEqualTo(now);
        assertThat(saved.getUpdatedAt()).isEqualTo(now);
        assertThat(saved.getTokenKeyVersion()).isEqualTo("v1");

        String token = generated.inviteUrl().substring(LINK_PREFIX.length());
        assertThat(generated.inviteUrl()).startsWith(LINK_PREFIX);
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(saved.getTokenHash()).isEqualTo(tokens.hash(token)).doesNotContain(token);
        assertThat(saved.getTokenCiphertext()).isNotEqualTo(token).doesNotContain(token);
        assertThat(cipher.decrypt(saved.getTokenCiphertext(), saved.getTokenKeyVersion())).isEqualTo(token);

        assertThat(generated.invitation().status()).isEqualTo(InvitationStatus.PENDING);
        assertThat(generated.invitation().role()).isEqualTo(CompanyRole.OPERATOR);
        assertThat(generated.invitation().version()).isEqualTo(1);
        assertThat(generated.invitation().createdBy().id()).isEqualTo(owner.getId());
        assertThat(generated.invitation().createdBy().name()).isEqualTo("João Rangel");
        assertThat(generated.invitation().acceptedAt()).isNull();
        assertThat(generated.invitation().revokedAt()).isNull();
    }

    @Test
    void create_neverCreatesAUserOrAMembershipNorTouchesAnExistingAccount() {
        // Arrange: a pessoa já tem conta, sem vínculo ativo
        UserEntity existing = UserEntity.builder().id(UUID.randomUUID()).fullName("Mariana Existente")
                .email("mariana@agrosul.com.br").passwordHash("hash").passwordSalt("salt").build();
        lenient().when(administratorRepository.existsByEmailIgnoreCase(any())).thenReturn(false);
        when(userRepository.findFirstByEmailIgnoreCase("mariana@agrosul.com.br")).thenReturn(Optional.of(existing));
        when(membershipRepository.findFirstByUserIdAndStatusOrderByRemovedAtDesc(existing.getId(), MembershipStatus.ACTIVE))
                .thenReturn(Optional.empty());
        savedInvitationsGetAnId();

        // Act
        GeneratedInvitationResponse generated = service.create(companyId, owner,
                new InvitationCreateRequest("Outro Nome", "mariana@agrosul.com.br", InviteRole.VIEWER));

        // Assert
        assertThat(generated.invitation().name()).isEqualTo("Outro Nome");
        assertThat(existing.getFullName()).isEqualTo("Mariana Existente");
        assertThat(existing.getPasswordHash()).isEqualTo("hash");
        verify(userRepository, never()).save(any());
        verify(userRepository, never()).saveAndFlush(any());
        verify(membershipRepository, never()).save(any());
        verify(membershipRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_activeMemberOfThisCompany_isMemberAlreadyExists() {
        // Arrange
        UserEntity member = recipientWithActiveMembershipIn(companyId);

        // Act / Assert
        assertThatThrownBy(() -> service.create(companyId, owner, request(member.getEmail())))
                .isInstanceOfSatisfying(ConflictException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("MEMBER_ALREADY_EXISTS");
                    assertThat(ex.getDetails()).isEmpty();
                });
        verify(invitationRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_pendingInvitationForTheEmail_isConflictWithTheExistingInvitationAndCreatesNothing() {
        // Arrange
        UUID existingId = UUID.randomUUID();
        lenient().when(administratorRepository.existsByEmailIgnoreCase(any())).thenReturn(false);
        CompanyInvitationEntity pending = invitation(InvitationStatus.PENDING, 2);
        pending.setId(existingId);
        when(invitationRepository.findFirstByCompanyIdAndStatusAndEmailIgnoreCase(
                companyId, InvitationStatus.PENDING, "mariana@agrosul.com.br")).thenReturn(Optional.of(pending));

        // Act / Assert
        assertThatThrownBy(() -> service.create(companyId, owner, request("Mariana@agrosul.com.br")))
                .isInstanceOfSatisfying(ConflictException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("INVITATION_ALREADY_EXISTS");
                    assertThat(ex.getDetails()).containsExactly(Map.entry("invitationId", existingId));
                });
        verify(invitationRepository, never()).saveAndFlush(any());
        verify(invitationRepository, never()).save(any());
        assertThat(pending.getVersion()).isEqualTo(2);
    }

    @Test
    void create_activeMemberOfAnotherCompany_isUserInOtherCompanyWithoutRevealingWhich() {
        // Arrange
        UserEntity elsewhere = recipientWithActiveMembershipIn(UUID.randomUUID());

        // Act / Assert
        assertThatThrownBy(() -> service.create(companyId, owner, request(elsewhere.getEmail())))
                .isInstanceOfSatisfying(ConflictException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("USER_IN_OTHER_COMPANY");
                    assertThat(ex.getDetails()).isEmpty();
                    assertThat(ex.getMessage()).doesNotContainIgnoringCase("agrosul");
                });
        verify(invitationRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_emailOfAnAdministrator_isReserved() {
        // Arrange
        when(administratorRepository.existsByEmailIgnoreCase("admin@vstable.test")).thenReturn(true);

        // Act / Assert
        assertThatThrownBy(() -> service.create(companyId, owner, request("Admin@VStable.test")))
                .isInstanceOfSatisfying(ConflictException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("EMAIL_RESERVED"));
        verifyNoInteractions(userRepository, membershipRepository);
        verify(invitationRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_pendingInvitationWinsOverAnotherCompanyBecauseTheOwnerCanRevokeIt() {
        // Arrange: a pessoa entrou em outra empresa depois de convidada aqui
        UserEntity elsewhere = recipientWithActiveMembershipIn(UUID.randomUUID());
        CompanyInvitationEntity pending = invitation(InvitationStatus.PENDING, 1);
        when(invitationRepository.findFirstByCompanyIdAndStatusAndEmailIgnoreCase(
                companyId, InvitationStatus.PENDING, elsewhere.getEmail())).thenReturn(Optional.of(pending));

        // Act / Assert
        assertThatThrownBy(() -> service.create(companyId, owner, request(elsewhere.getEmail())))
                .isInstanceOfSatisfying(ConflictException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("INVITATION_ALREADY_EXISTS"));
    }

    @Test
    void create_lostTheRaceToAnotherRequestForTheSameEmail_answersWithTheConflictOfTheWinner() {
        // Arrange: nenhum pendente na checagem, mas o índice único recusa a gravação
        noConflicts();
        UUID winnerId = UUID.randomUUID();
        CompanyInvitationEntity winner = invitation(InvitationStatus.PENDING, 1);
        winner.setId(winnerId);
        when(invitationRepository.findFirstByCompanyIdAndStatusAndEmailIgnoreCase(
                companyId, InvitationStatus.PENDING, "mariana@agrosul.com.br"))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(invitationRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

        // Act / Assert
        assertThatThrownBy(() -> service.create(companyId, owner, request("mariana@agrosul.com.br")))
                .isInstanceOfSatisfying(ConflictException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("INVITATION_ALREADY_EXISTS");
                    assertThat(ex.getDetails()).containsEntry("invitationId", winnerId);
                });
    }

    @Test
    void create_integrityViolationWithoutAPendingWinner_isNotHiddenAsAConflict() {
        // Arrange
        noConflicts();
        when(invitationRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("other"));

        // Act / Assert
        assertThatThrownBy(() -> service.create(companyId, owner, request("mariana@agrosul.com.br")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- regeneração (#73) ----

    @Test
    void regenerate_pendingInvitation_replacesTheTokenAndKeepsTheHashOfTheOldOneAsHistory() {
        // Arrange
        CompanyInvitationEntity invitation = lockedInvitation(InvitationStatus.PENDING, 3, now.minusHours(2));
        invitation.setTokenKeyVersion("v0");
        String oldHash = invitation.getTokenHash();
        UUID id = invitation.getId();
        OffsetDateTime createdAt = invitation.getCreatedAt();

        // Act
        GeneratedInvitationResponse generated = service.regenerate(companyId, id, 3);

        // Assert
        String newToken = generated.inviteUrl().substring(LINK_PREFIX.length());
        assertThat(generated.inviteUrl()).startsWith(LINK_PREFIX);
        assertThat(newToken).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(TOKEN);
        assertThat(invitation.getTokenHash()).isEqualTo(tokens.hash(newToken)).isNotEqualTo(oldHash);
        assertThat(invitation.getTokenKeyVersion()).isEqualTo("v1");
        assertThat(cipher.decrypt(invitation.getTokenCiphertext(), invitation.getTokenKeyVersion())).isEqualTo(newToken);
        assertThat(invitation.getVersion()).isEqualTo(4);
        assertThat(invitation.getUpdatedAt()).isEqualTo(now);
        assertThat(invitation.getId()).isEqualTo(id);
        assertThat(invitation.getCreatedAt()).isEqualTo(createdAt);
        assertThat(invitation.getName()).isEqualTo("Mariana Costa");
        assertThat(invitation.getEmail()).isEqualTo("mariana@agrosul.com.br");
        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.PENDING);
        assertThat(generated.invitation().version()).isEqualTo(4);

        CompanyInvitationReplacedTokenEntity history = savedReplacedToken();
        assertThat(history.getInvitationId()).isEqualTo(id);
        assertThat(history.getTokenHash()).isEqualTo(oldHash);
        assertThat(history.getReplacedAt()).isEqualTo(now);
        assertThat(history.toString()).doesNotContain(TOKEN);
        verify(invitationRepository).saveAndFlush(invitation);
    }

    @Test
    void regenerate_neverCreatesUsersOrMemberships() {
        // Arrange
        CompanyInvitationEntity invitation = lockedInvitation(InvitationStatus.PENDING, 1, now.minusHours(2));

        // Act
        service.regenerate(companyId, invitation.getId(), 1);

        // Assert
        verifyNoInteractions(membershipRepository, administratorRepository);
        verify(userRepository, never()).save(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void regenerate_staleVersion_isPreconditionFailedAndChangesNothing() {
        // Arrange
        CompanyInvitationEntity invitation = lockedInvitation(InvitationStatus.PENDING, 3, now.minusHours(2));
        String hash = invitation.getTokenHash();

        // Act / Assert
        assertThatThrownBy(() -> service.regenerate(companyId, invitation.getId(), 2))
                .isInstanceOfSatisfying(PreconditionFailedException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("RESOURCE_VERSION_MISMATCH"));
        assertThat(invitation.getTokenHash()).isEqualTo(hash);
        assertThat(invitation.getVersion()).isEqualTo(3);
        verify(invitationRepository, never()).saveAndFlush(any());
        verifyNoInteractions(replacedTokenRepository);
    }

    @Test
    void regenerate_acceptedOrRevokedInvitation_isNotRegenerableEvenWithAStaleVersion() {
        for (InvitationStatus status : new InvitationStatus[]{InvitationStatus.ACCEPTED, InvitationStatus.REVOKED}) {
            // Arrange
            CompanyInvitationEntity invitation = lockedInvitation(status, 5, now.minusHours(2));

            // Act / Assert
            assertThatThrownBy(() -> service.regenerate(companyId, invitation.getId(), 1))
                    .isInstanceOfSatisfying(ConflictException.class,
                            ex -> assertThat(ex.getCode()).isEqualTo("INVITATION_NOT_REGENERABLE"));
            assertThat(invitation.getVersion()).isEqualTo(5);
        }
        verify(invitationRepository, never()).saveAndFlush(any());
        verifyNoInteractions(replacedTokenRepository);
    }

    @Test
    void regenerate_unknownInvitationOrOneFromAnotherCompany_isNotFound() {
        // Arrange
        UUID invitationId = UUID.randomUUID();
        when(invitationRepository.findForUpdate(invitationId, companyId)).thenReturn(Optional.empty());

        // Act / Assert
        assertThatThrownBy(() -> service.regenerate(companyId, invitationId, 1))
                .isInstanceOfSatisfying(NotFoundException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("INVITATION_NOT_FOUND"));
    }

    @Test
    void regenerate_lessThan60SecondsAfterTheCreation_isRateLimitedWithTheSecondsToWait() {
        // Arrange
        CompanyInvitationEntity invitation = lockedInvitation(InvitationStatus.PENDING, 1, now.minusSeconds(20));
        String hash = invitation.getTokenHash();

        // Act / Assert
        assertThatThrownBy(() -> service.regenerate(companyId, invitation.getId(), 1))
                .isInstanceOfSatisfying(TooManyRequestsException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("INVITATION_RATE_LIMITED");
                    assertThat(ex.getRetryAfterSeconds()).isEqualTo(40);
                });
        assertThat(invitation.getTokenHash()).isEqualTo(hash);
        verify(invitationRepository, never()).saveAndFlush(any());
        verify(replacedTokenRepository, never()).save(any());
    }

    @Test
    void regenerate_60SecondsAfterTheLastGeneration_isAllowed() {
        // Arrange
        CompanyInvitationEntity invitation = lockedInvitation(InvitationStatus.PENDING, 1, now.minusSeconds(60));

        // Act
        GeneratedInvitationResponse generated = service.regenerate(companyId, invitation.getId(), 1);

        // Assert
        assertThat(generated.invitation().version()).isEqualTo(2);
    }

    @Test
    void regenerate_lessThan60SecondsAfterAPreviousRegeneration_isRateLimited() {
        // Arrange: criado há 30 minutos e regenerado há 10 segundos
        CompanyInvitationEntity invitation = lockedInvitation(InvitationStatus.PENDING, 2, now.minusMinutes(30));
        replacedAt(invitation, now.minusSeconds(10));

        // Act / Assert
        assertThatThrownBy(() -> service.regenerate(companyId, invitation.getId(), 2))
                .isInstanceOfSatisfying(TooManyRequestsException.class,
                        ex -> assertThat(ex.getRetryAfterSeconds()).isEqualTo(50));
    }

    @Test
    void regenerate_afterFourGenerationsInTheHour_theFifthIsAllowed() {
        // Arrange: criação e três regenerações, a última há 20 minutos
        CompanyInvitationEntity invitation = lockedInvitation(InvitationStatus.PENDING, 4, now.minusMinutes(50));
        replacedAt(invitation, now.minusMinutes(40), now.minusMinutes(30), now.minusMinutes(20));

        // Act
        GeneratedInvitationResponse generated = service.regenerate(companyId, invitation.getId(), 4);

        // Assert
        assertThat(generated.invitation().version()).isEqualTo(5);
    }

    @Test
    void regenerate_afterFiveGenerationsInTheHourCountingTheCreation_isRefusedUntilTheOldestLeavesTheWindow() {
        // Arrange: criação há 50 minutos e quatro regenerações; a criação sai da janela em 10 minutos
        CompanyInvitationEntity invitation = lockedInvitation(InvitationStatus.PENDING, 5, now.minusMinutes(50));
        replacedAt(invitation, now.minusMinutes(40), now.minusMinutes(30), now.minusMinutes(20), now.minusMinutes(10));
        String hash = invitation.getTokenHash();

        // Act / Assert
        assertThatThrownBy(() -> service.regenerate(companyId, invitation.getId(), 5))
                .isInstanceOfSatisfying(TooManyRequestsException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("INVITATION_RATE_LIMITED");
                    assertThat(ex.getRetryAfterSeconds()).isEqualTo(Duration.ofMinutes(10).toSeconds());
                });
        assertThat(invitation.getTokenHash()).isEqualTo(hash);
        assertThat(invitation.getVersion()).isEqualTo(5);
        verify(invitationRepository, never()).saveAndFlush(any());
        verify(replacedTokenRepository, never()).save(any());
    }

    @Test
    void regenerate_generationsOlderThanOneHour_doNotCountTowardsTheLimit() {
        // Arrange: criação há 2 horas (fora da janela) e quatro regenerações dentro dela
        CompanyInvitationEntity invitation = lockedInvitation(InvitationStatus.PENDING, 5, now.minusHours(2));
        replacedAt(invitation, now.minusMinutes(50), now.minusMinutes(40), now.minusMinutes(30), now.minusMinutes(20));

        // Act
        GeneratedInvitationResponse generated = service.regenerate(companyId, invitation.getId(), 5);

        // Assert
        assertThat(generated.invitation().version()).isEqualTo(6);
        verify(replacedTokenRepository).findByInvitationIdAndReplacedAtAfter(invitation.getId(), now.minusHours(1));
    }

    // ---- revogação (#73) ----

    @Test
    void revoke_pendingInvitation_invalidatesTheLinkAndEliminatesTheRecoverableCopy() {
        // Arrange
        CompanyInvitationEntity invitation = lockedInvitation(InvitationStatus.PENDING, 2, now.minusHours(2));
        String hash = invitation.getTokenHash();

        // Act
        service.revoke(companyId, invitation.getId(), 2);

        // Assert
        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.REVOKED);
        assertThat(invitation.getRevokedAt()).isEqualTo(now);
        assertThat(invitation.getTokenCiphertext()).isNull();
        assertThat(invitation.getTokenKeyVersion()).isNull();
        assertThat(invitation.getTokenHash()).isEqualTo(hash);
        assertThat(invitation.getVersion()).isEqualTo(3);
        assertThat(invitation.getUpdatedAt()).isEqualTo(now);
        verify(invitationRepository).saveAndFlush(invitation);
        verifyNoInteractions(membershipRepository);
        verify(userRepository, never()).save(any());
    }

    @Test
    void revoke_alreadyRevoked_isIdempotentEvenWithAnOldVersion() {
        // Arrange
        CompanyInvitationEntity invitation = lockedInvitation(InvitationStatus.REVOKED, 5, now.minusHours(2));

        // Act
        service.revoke(companyId, invitation.getId(), 1);

        // Assert
        assertThat(invitation.getVersion()).isEqualTo(5);
        verify(invitationRepository, never()).saveAndFlush(any());
    }

    @Test
    void revoke_acceptedInvitation_isAlreadyUsed() {
        // Arrange
        CompanyInvitationEntity invitation = lockedInvitation(InvitationStatus.ACCEPTED, 2, now.minusHours(2));

        // Act / Assert
        assertThatThrownBy(() -> service.revoke(companyId, invitation.getId(), 2))
                .isInstanceOfSatisfying(ConflictException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("INVITATION_ALREADY_USED"));
        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
        verify(invitationRepository, never()).saveAndFlush(any());
    }

    @Test
    void revoke_staleVersion_isPreconditionFailedAndTheInvitationStaysPending() {
        // Arrange
        CompanyInvitationEntity invitation = lockedInvitation(InvitationStatus.PENDING, 3, now.minusHours(2));

        // Act / Assert
        assertThatThrownBy(() -> service.revoke(companyId, invitation.getId(), 2))
                .isInstanceOfSatisfying(PreconditionFailedException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("RESOURCE_VERSION_MISMATCH"));
        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.PENDING);
        assertThat(invitation.getTokenCiphertext()).isNotNull();
        verify(invitationRepository, never()).saveAndFlush(any());
    }

    @Test
    void revoke_unknownInvitationOrOneFromAnotherCompany_isNotFound() {
        // Arrange
        UUID invitationId = UUID.randomUUID();
        when(invitationRepository.findForUpdate(invitationId, companyId)).thenReturn(Optional.empty());

        // Act / Assert
        assertThatThrownBy(() -> service.revoke(companyId, invitationId, 1))
                .isInstanceOfSatisfying(NotFoundException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("INVITATION_NOT_FOUND"));
    }

    // ---- apoio ----

    private InvitationCreateRequest request(String email) {
        return new InvitationCreateRequest("Mariana Costa", email, InviteRole.OPERATOR);
    }

    /** Ninguém é administrador, conta existente ou convidado pendente com o e-mail. */
    private void noConflicts() {
        lenient().when(administratorRepository.existsByEmailIgnoreCase(any())).thenReturn(false);
        lenient().when(userRepository.findFirstByEmailIgnoreCase(any())).thenReturn(Optional.empty());
        lenient().when(invitationRepository.findFirstByCompanyIdAndStatusAndEmailIgnoreCase(any(), any(), any()))
                .thenReturn(Optional.empty());
    }

    private void savedInvitationsGetAnId() {
        lenient().when(administratorRepository.existsByEmailIgnoreCase(any())).thenReturn(false);
        lenient().when(invitationRepository.findFirstByCompanyIdAndStatusAndEmailIgnoreCase(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(invitationRepository.saveAndFlush(any())).thenAnswer(call -> {
            CompanyInvitationEntity entity = call.getArgument(0);
            entity.setId(UUID.randomUUID());
            return entity;
        });
    }

    private UserEntity recipientWithActiveMembershipIn(UUID membershipCompanyId) {
        UserEntity recipient = UserEntity.builder().id(UUID.randomUUID()).fullName("Pessoa Vinculada")
                .email("pessoa@agrosul.com.br").build();
        lenient().when(administratorRepository.existsByEmailIgnoreCase(any())).thenReturn(false);
        when(userRepository.findFirstByEmailIgnoreCase(recipient.getEmail())).thenReturn(Optional.of(recipient));
        when(membershipRepository.findFirstByUserIdAndStatusOrderByRemovedAtDesc(recipient.getId(), MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(CompanyMembershipEntity.builder().id(UUID.randomUUID())
                        .companyId(membershipCompanyId).userId(recipient.getId()).role(CompanyRole.OPERATOR)
                        .status(MembershipStatus.ACTIVE).build()));
        return recipient;
    }

    private CompanyInvitationEntity savedInvitation() {
        ArgumentCaptor<CompanyInvitationEntity> captor = ArgumentCaptor.forClass(CompanyInvitationEntity.class);
        verify(invitationRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private CompanyInvitationReplacedTokenEntity savedReplacedToken() {
        ArgumentCaptor<CompanyInvitationReplacedTokenEntity> captor =
                ArgumentCaptor.forClass(CompanyInvitationReplacedTokenEntity.class);
        verify(replacedTokenRepository).save(captor.capture());
        return captor.getValue();
    }

    /** Registra as substituições do convite que o repositório devolve para a janela da última hora. */
    private void replacedAt(CompanyInvitationEntity invitation, OffsetDateTime... instants) {
        List<CompanyInvitationReplacedTokenEntity> replaced = Arrays.stream(instants)
                .map(instant -> CompanyInvitationReplacedTokenEntity.builder()
                        .invitationId(invitation.getId()).tokenHash(UUID.randomUUID().toString()).replacedAt(instant)
                        .build())
                .toList();
        when(replacedTokenRepository.findByInvitationIdAndReplacedAtAfter(eq(invitation.getId()), any()))
                .thenReturn(replaced);
    }

    private CompanyInvitationEntity lockedInvitation(InvitationStatus status, long version, OffsetDateTime createdAt) {
        CompanyInvitationEntity invitation = invitation(status, version);
        invitation.setCreatedAt(createdAt);
        invitation.setUpdatedAt(createdAt);
        when(invitationRepository.findForUpdate(invitation.getId(), companyId)).thenReturn(Optional.of(invitation));
        lenient().when(userRepository.findById(owner.getId())).thenReturn(Optional.of(owner));
        return invitation;
    }

    private void found(CompanyInvitationEntity invitation) {
        when(invitationRepository.findByIdAndCompanyId(invitation.getId(), companyId)).thenReturn(Optional.of(invitation));
        lenient().when(userRepository.findById(owner.getId())).thenReturn(Optional.of(owner));
    }

    private CompanyInvitationEntity invitation(InvitationStatus status, long version) {
        boolean pending = status == InvitationStatus.PENDING;
        return CompanyInvitationEntity.builder()
                .id(UUID.randomUUID())
                .companyId(companyId)
                .name("Mariana Costa")
                .email("mariana@agrosul.com.br")
                .role(CompanyRole.OPERATOR)
                .status(status)
                .version(version)
                .tokenHash(tokens.hash(TOKEN))
                .tokenCiphertext(pending ? cipher.encrypt(TOKEN) : null)
                .tokenKeyVersion(pending ? "v1" : null)
                .createdBy(owner.getId())
                .createdAt(now.withOffsetSameInstant(ZoneOffset.ofHours(-3)))
                .updatedAt(now)
                .acceptedAt(status == InvitationStatus.ACCEPTED ? now : null)
                .revokedAt(status == InvitationStatus.REVOKED ? now : null)
                .build();
    }
}
