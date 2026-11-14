package ages.vstable.backend.service;

import ages.vstable.backend.dto.membership.CompanyAccessResponse;
import ages.vstable.backend.entity.CompanyMembershipEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.CompanyPermission;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.MembershipStatus;
import ages.vstable.backend.repository.CompanyMembershipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompanyMembershipServiceTest {

    @Mock
    private CompanyMembershipRepository membershipRepository;

    private CompanyMembershipService service;

    @BeforeEach
    void setUp() {
        service = new CompanyMembershipService(membershipRepository);
    }

    @Test
    void assignOwner_savesActiveOwnerMembershipStartingAtVersionOne() {
        // Arrange
        UUID userId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        when(membershipRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        CompanyMembershipEntity returned = service.assignOwner(userId, companyId, now);

        // Assert
        ArgumentCaptor<CompanyMembershipEntity> captor = ArgumentCaptor.forClass(CompanyMembershipEntity.class);
        verify(membershipRepository).saveAndFlush(captor.capture());
        CompanyMembershipEntity saved = captor.getValue();
        assertThat(returned).isNotNull().isSameAs(saved);
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getCompanyId()).isEqualTo(companyId);
        assertThat(saved.getRole()).isEqualTo(CompanyRole.OWNER);
        assertThat(saved.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(saved.getVersion()).isEqualTo(1);
        assertThat(saved.getJoinedAt()).isEqualTo(now);
        assertThat(saved.getUpdatedAt()).isEqualTo(now);
        assertThat(saved.getRemovedAt()).isNull();
    }

    @Test
    void findCurrentMembership_activeMembershipExists_returnsItWithoutLookingForRemovedOnes() {
        // Arrange
        UUID userId = UUID.randomUUID();
        CompanyMembershipEntity active = membership(userId, CompanyRole.VIEWER, MembershipStatus.ACTIVE, 1);
        when(membershipRepository.findFirstByUserIdAndStatusOrderByRemovedAtDesc(userId, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(active));

        // Act
        Optional<CompanyMembershipEntity> current = service.findCurrentMembership(userId);

        // Assert
        assertThat(current).containsSame(active);
        verify(membershipRepository, never())
                .findFirstByUserIdAndStatusOrderByRemovedAtDesc(userId, MembershipStatus.REMOVED);
    }

    @Test
    void findCurrentMembership_onlyRemovedMembership_fallsBackToTheLatestRemovedOne() {
        // Arrange
        UUID userId = UUID.randomUUID();
        CompanyMembershipEntity removed = membership(userId, CompanyRole.OWNER, MembershipStatus.REMOVED, 3);
        when(membershipRepository.findFirstByUserIdAndStatusOrderByRemovedAtDesc(userId, MembershipStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(membershipRepository.findFirstByUserIdAndStatusOrderByRemovedAtDesc(userId, MembershipStatus.REMOVED))
                .thenReturn(Optional.of(removed));

        // Act / Assert
        assertThat(service.findCurrentMembership(userId)).containsSame(removed);
    }

    @Test
    void findCurrentMembership_userNeverLinkedToCompany_returnsEmpty() {
        // Arrange
        UUID userId = UUID.randomUUID();
        when(membershipRepository.findFirstByUserIdAndStatusOrderByRemovedAtDesc(userId, MembershipStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(membershipRepository.findFirstByUserIdAndStatusOrderByRemovedAtDesc(userId, MembershipStatus.REMOVED))
                .thenReturn(Optional.empty());

        // Act / Assert
        assertThat(service.findCurrentMembership(userId)).isEmpty();
    }

    @Test
    void currentCompanyId_withoutAnyMembership_keepsCompanyStoredOnTheAccount() {
        // Arrange
        UserEntity user = UserEntity.builder().id(UUID.randomUUID()).companyId(UUID.randomUUID()).build();

        // Act / Assert
        assertThat(CompanyMembershipService.currentCompanyId(user)).isEqualTo(user.getCompanyId());
    }

    @Test
    void currentCompanyId_activeMembership_returnsCompanyOfTheMembership() {
        // Arrange: a conta guarda outra empresa; vale a do vínculo.
        UserEntity user = UserEntity.builder().id(UUID.randomUUID()).companyId(UUID.randomUUID()).build();
        user.setMembership(membership(user.getId(), CompanyRole.VIEWER, MembershipStatus.ACTIVE, 1));

        // Act / Assert
        assertThat(CompanyMembershipService.currentCompanyId(user)).isEqualTo(user.getMembership().getCompanyId());
    }

    @Test
    void currentCompanyId_removedMembership_returnsNoCompany() {
        // Arrange
        UserEntity user = UserEntity.builder().id(UUID.randomUUID()).companyId(UUID.randomUUID()).build();
        user.setMembership(membership(user.getId(), CompanyRole.OPERATOR, MembershipStatus.REMOVED, 2));

        // Act / Assert
        assertThat(CompanyMembershipService.currentCompanyId(user)).isNull();
    }

    @Test
    void toAccessResponse_activeMembership_carriesTheRoleAndThePermissionsOfThatRole() {
        // Arrange
        CompanyMembershipEntity membership = membership(UUID.randomUUID(), CompanyRole.OPERATOR, MembershipStatus.ACTIVE, 3);

        // Act
        CompanyAccessResponse access = CompanyMembershipService.toAccessResponse(membership);

        // Assert
        assertThat(access.membershipId()).isEqualTo(membership.getId());
        assertThat(access.companyId()).isEqualTo(membership.getCompanyId());
        assertThat(access.role()).isEqualTo(CompanyRole.OPERATOR);
        assertThat(access.status()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(access.permissions()).containsExactlyElementsOf(
                CompanyRole.OPERATOR.permissionsFor(MembershipStatus.ACTIVE));
        assertThat(access.version()).isEqualTo(3);
    }

    @Test
    void toAccessResponse_removedMembership_onlyAllowsReadingOwnAccess() {
        // Arrange
        CompanyMembershipEntity membership = membership(UUID.randomUUID(), CompanyRole.OWNER, MembershipStatus.REMOVED, 2);

        // Act
        CompanyAccessResponse access = CompanyMembershipService.toAccessResponse(membership);

        // Assert
        assertThat(access.status()).isEqualTo(MembershipStatus.REMOVED);
        assertThat(access.permissions()).containsExactly(CompanyPermission.SELF_MEMBERSHIP_READ);
    }

    private static CompanyMembershipEntity membership(
            UUID userId, CompanyRole role, MembershipStatus status, long version) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return CompanyMembershipEntity.builder()
                .id(UUID.randomUUID())
                .companyId(UUID.randomUUID())
                .userId(userId)
                .role(role)
                .status(status)
                .version(version)
                .joinedAt(now)
                .removedAt(status == MembershipStatus.REMOVED ? now : null)
                .updatedAt(now)
                .build();
    }
}
