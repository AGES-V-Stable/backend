package ages.vstable.backend.service;

import ages.vstable.backend.dto.membership.InviteRole;
import ages.vstable.backend.entity.CompanyMembershipEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.MembershipStatus;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.exception.PreconditionFailedException;
import ages.vstable.backend.repository.CompanyInvitationRepository;
import ages.vstable.backend.repository.CompanyMembershipRepository;
import ages.vstable.backend.repository.UserRepository;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompanyMemberManagementServiceTest {
    private final UUID companyId = UUID.randomUUID();
    private final UUID memberId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @Mock CompanyMembershipRepository memberships;
    @Mock CompanyInvitationRepository invitations;
    @Mock UserRepository users;

    private CompanyMemberService service;
    private Logger auditLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setup() {
        service = new CompanyMemberService(memberships, invitations, users);
        auditLogger = (Logger) LoggerFactory.getLogger(CompanyMemberService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        auditLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        auditLogger.detachAppender(logAppender);
        TransactionSynchronizationManager.clear();
    }

    private CompanyMembershipEntity member(CompanyRole role, MembershipStatus status) {
        OffsetDateTime past = OffsetDateTime.now(ZoneOffset.UTC).minusHours(2);
        return CompanyMembershipEntity.builder()
                .id(memberId).companyId(companyId).userId(userId)
                .role(role).status(status).version(3)
                .joinedAt(past)
                .updatedAt(past).build();
    }

    private void found(CompanyMembershipEntity member) {
        when(memberships.findForUpdate(memberId, companyId)).thenReturn(Optional.of(member));
    }

    @Test
    void changeRole_updatesRoleVersionAndKeepsIdentity() {
        var member = member(CompanyRole.OPERATOR, MembershipStatus.ACTIVE);
        OffsetDateTime previousUpdatedAt = member.getUpdatedAt();
        var user = UserEntity.builder().id(userId).fullName("Usuaria").email("user@example.com").build();
        found(member);
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(memberships.saveAndFlush(member)).thenReturn(member);

        var response = service.changeRole(companyId, memberId, 3, InviteRole.VIEWER, actorId);

        assertThat(response.role()).isEqualTo(CompanyRole.VIEWER);
        assertThat(response.version()).isEqualTo(4);
        assertThat(response.userId()).isEqualTo(userId);
        assertThat(member.getUpdatedAt()).isAfter(previousUpdatedAt);
        verify(memberships).saveAndFlush(member);
        assertThat(logAppender.list).hasSize(1);
        assertThat(logAppender.list.get(0).getFormattedMessage()).isEqualTo("Company membership access changed");
    }

    @Test
    void changeRole_whenUserLinkedToMembershipIsNotFound_throwsIllegalStateException() {
        var member = member(CompanyRole.OPERATOR, MembershipStatus.ACTIVE);
        found(member);
        when(users.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeRole(companyId, memberId, 3, InviteRole.OPERATOR, actorId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("User linked to membership not found");
    }

    @Test
    void changeRole_refusesOwnerEvenWithCorrectVersion() {
        found(member(CompanyRole.OWNER, MembershipStatus.ACTIVE));
        assertThatThrownBy(() -> service.changeRole(companyId, memberId, 3, InviteRole.VIEWER, actorId))
                .isInstanceOfSatisfying(ConflictException.class, error ->
                        assertThat(error.getCode()).isEqualTo("OWNER_PROTECTED"));
        verify(memberships, never()).saveAndFlush(any());
    }

    @Test
    void changeRole_rejectsStaleVersion() {
        found(member(CompanyRole.OPERATOR, MembershipStatus.ACTIVE));
        assertThatThrownBy(() -> service.changeRole(companyId, memberId, 2, InviteRole.VIEWER, actorId))
                .isInstanceOfSatisfying(PreconditionFailedException.class, error ->
                        assertThat(error.getCode()).isEqualTo("RESOURCE_VERSION_MISMATCH"));
        verify(memberships, never()).saveAndFlush(any());
    }

    @Test
    void changeRole_refusesRemovedMembership() {
        found(member(CompanyRole.OPERATOR, MembershipStatus.REMOVED));
        assertThatThrownBy(() -> service.changeRole(companyId, memberId, 3, InviteRole.VIEWER, actorId))
                .isInstanceOfSatisfying(ConflictException.class, error ->
                        assertThat(error.getCode()).isEqualTo("MEMBERSHIP_NOT_ACTIVE"));
    }

    @Test
    void revoke_keepsPersonalAccountAndRevokesMembership() {
        var member = member(CompanyRole.OPERATOR, MembershipStatus.ACTIVE);
        OffsetDateTime previousUpdatedAt = member.getUpdatedAt();
        found(member);
        when(memberships.saveAndFlush(member)).thenReturn(member);

        service.revoke(companyId, memberId, 3, actorId);

        assertThat(member.getStatus()).isEqualTo(MembershipStatus.REMOVED);
        assertThat(member.getRemovedAt()).isNotNull();
        assertThat(member.getUpdatedAt()).isEqualTo(member.getRemovedAt()).isAfter(previousUpdatedAt);
        assertThat(member.getVersion()).isEqualTo(4);
        verify(memberships).saveAndFlush(member);
        verify(users, never()).delete(any());
        assertThat(logAppender.list).hasSize(1);
        assertThat(logAppender.list.get(0).getFormattedMessage()).isEqualTo("Company membership access changed");
    }

    @Test
    void revoke_rejectsStaleVersion() {
        found(member(CompanyRole.OPERATOR, MembershipStatus.ACTIVE));

        assertThatThrownBy(() -> service.revoke(companyId, memberId, 2, actorId))
                .isInstanceOfSatisfying(PreconditionFailedException.class, error ->
                        assertThat(error.getCode()).isEqualTo("RESOURCE_VERSION_MISMATCH"));
        verify(memberships, never()).saveAndFlush(any());
    }

    @Test
    void auditAfterCommit_defersAuditUntilCommitWhenTransactionSynchronizationIsActive() {
        var member = member(CompanyRole.OPERATOR, MembershipStatus.ACTIVE);
        found(member);
        when(memberships.saveAndFlush(member)).thenReturn(member);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        service.revoke(companyId, memberId, 3, actorId);

        assertThat(logAppender.list).isEmpty();
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(logAppender.list).hasSize(1);
        assertThat(logAppender.list.get(0).getFormattedMessage()).isEqualTo("Company membership access changed");
    }

    @Test
    void auditAfterCommit_emitsImmediatelyWhenTransactionIsActiveWithoutSynchronization() {
        var member = member(CompanyRole.OPERATOR, MembershipStatus.ACTIVE);
        found(member);
        when(memberships.saveAndFlush(member)).thenReturn(member);
        TransactionSynchronizationManager.setActualTransactionActive(true);

        service.revoke(companyId, memberId, 3, actorId);

        assertThat(logAppender.list).hasSize(1);
        assertThat(logAppender.list.get(0).getFormattedMessage()).isEqualTo("Company membership access changed");
    }

    @Test
    void revoke_isIdempotentForAlreadyRemoved() {
        found(member(CompanyRole.OPERATOR, MembershipStatus.REMOVED));
        service.revoke(companyId, memberId, 0, actorId);
        verify(memberships, never()).saveAndFlush(any());
    }

    @Test
    void revoke_protectsOwner() {
        found(member(CompanyRole.OWNER, MembershipStatus.ACTIVE));
        assertThatThrownBy(() -> service.revoke(companyId, memberId, 3, actorId))
                .isInstanceOfSatisfying(ConflictException.class, error ->
                        assertThat(error.getCode()).isEqualTo("OWNER_PROTECTED"));
    }

    @Test
    void hiddenOtherCompanyMember_isNotFound() {
        when(memberships.findForUpdate(memberId, companyId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.revoke(companyId, memberId, 3, actorId))
                .isInstanceOfSatisfying(NotFoundException.class, error ->
                        assertThat(error.getCode()).isEqualTo("MEMBER_NOT_FOUND"));
    }
}
