package ages.vstable.backend.service;

import ages.vstable.backend.dto.membership.MemberListItemResponse;
import ages.vstable.backend.dto.membership.MemberListResponse;
import ages.vstable.backend.entity.CompanyInvitationEntity;
import ages.vstable.backend.entity.CompanyMembershipEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.InvitationStatus;
import ages.vstable.backend.entity.enums.MembershipStatus;
import ages.vstable.backend.exception.InvalidParameterException;
import ages.vstable.backend.repository.CompanyInvitationRepository;
import ages.vstable.backend.repository.CompanyMembershipRepository;
import ages.vstable.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class CompanyMemberServiceTest {

    private final UUID companyId = UUID.randomUUID();
    private final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    @Mock
    private CompanyMembershipRepository membershipRepository;

    @Mock
    private CompanyInvitationRepository invitationRepository;

    @Mock
    private UserRepository userRepository;

    private CompanyMemberService service;

    private final List<CompanyMembershipEntity> memberships = new ArrayList<>();
    private final List<CompanyInvitationEntity> invitations = new ArrayList<>();
    private final List<UserEntity> users = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new CompanyMemberService(membershipRepository, invitationRepository, userRepository);
        lenient().when(membershipRepository.findByCompanyIdAndStatus(companyId, MembershipStatus.ACTIVE))
                .thenReturn(memberships);
        lenient().when(invitationRepository.findByCompanyIdAndStatus(companyId, InvitationStatus.PENDING))
                .thenReturn(invitations);
        lenient().when(userRepository.findAllById(any())).thenAnswer(invocation -> {
            Iterable<UUID> requestedIds = invocation.getArgument(0);
            Set<UUID> idSet = new HashSet<>();
            requestedIds.forEach(idSet::add);
            return users.stream().filter(u -> idSet.contains(u.getId())).toList();
        });
    }

    @Test
    void list_putsTheOwnerFirstAndTheOthersByNameIgnoringCase() {
        // Arrange
        addMember("Zoe Owner", CompanyRole.OWNER);
        addMember("bruno", CompanyRole.OPERATOR);
        addMember("Ana", CompanyRole.VIEWER);
        addInvitation("carla", CompanyRole.OPERATOR);

        // Act
        MemberListResponse response = service.list(companyId, 0, 20);

        // Assert
        assertThat(names(response)).containsExactly("Zoe Owner", "Ana", "bruno", "carla");
        assertThat(response.items()).extracting(MemberListItemResponse::kind).containsExactly(
                MemberListItemResponse.Kind.MEMBER, MemberListItemResponse.Kind.MEMBER,
                MemberListItemResponse.Kind.MEMBER, MemberListItemResponse.Kind.INVITATION);
    }

    @Test
    void list_onNameTie_putsTheMemberBeforeTheInvitation() {
        // Arrange
        addMember("Dono", CompanyRole.OWNER);
        addInvitation("Ana", CompanyRole.VIEWER);
        addMember("ana", CompanyRole.OPERATOR);

        // Act
        MemberListResponse response = service.list(companyId, 0, 20);

        // Assert
        assertThat(response.items()).extracting(MemberListItemResponse::kind).containsExactly(
                MemberListItemResponse.Kind.MEMBER, MemberListItemResponse.Kind.MEMBER,
                MemberListItemResponse.Kind.INVITATION);
    }

    @Test
    void list_onlyTheOwner_distinguishesTheOwnerFromAnAbsentTeam() {
        // Arrange
        addMember("Dono", CompanyRole.OWNER);

        // Act
        MemberListResponse response = service.list(companyId, 0, 20);

        // Assert
        assertThat(response.items()).hasSize(1);
        assertThat(response.summary()).isEqualTo(new MemberListResponse.Summary(1, 0, 1));
        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.totalPages()).isEqualTo(1);
    }

    @Test
    void list_summaryCountsTheWholeSetNotJustThePage() {
        // Arrange
        addMember("Dono", CompanyRole.OWNER);
        addMember("Ana", CompanyRole.OPERATOR);
        addMember("Bia", CompanyRole.VIEWER);
        addInvitation("Caio", CompanyRole.OPERATOR);
        addInvitation("Duda", CompanyRole.VIEWER);

        // Act
        MemberListResponse firstPage = service.list(companyId, 0, 2);
        MemberListResponse lastPage = service.list(companyId, 2, 2);

        // Assert
        assertThat(firstPage.summary()).isEqualTo(new MemberListResponse.Summary(3, 2, 5));
        assertThat(firstPage.totalElements()).isEqualTo(5);
        assertThat(firstPage.totalPages()).isEqualTo(3);
        assertThat(names(firstPage)).containsExactly("Dono", "Ana");
        assertThat(names(lastPage)).containsExactly("Duda");
        assertThat(lastPage.page()).isEqualTo(2);
        assertThat(lastPage.size()).isEqualTo(2);
    }

    @Test
    void list_pageBeyondTheEnd_returnsNoItemsWithTheCorrectTotals() {
        // Arrange
        addMember("Dono", CompanyRole.OWNER);
        addInvitation("Ana", CompanyRole.OPERATOR);

        // Act
        MemberListResponse response = service.list(companyId, 7, 20);

        // Assert
        assertThat(response.items()).isEmpty();
        assertThat(response.totalElements()).isEqualTo(2);
        assertThat(response.totalPages()).isEqualTo(1);
        assertThat(response.summary()).isEqualTo(new MemberListResponse.Summary(1, 1, 2));
    }

    @Test
    void list_readsOnlyActiveMembersAndPendingInvitationsOfTheRequestedCompany() {
        // Act
        service.list(companyId, 0, 20);

        // Assert
        verify(membershipRepository).findByCompanyIdAndStatus(companyId, MembershipStatus.ACTIVE);
        verify(invitationRepository).findByCompanyIdAndStatus(companyId, InvitationStatus.PENDING);
    }

    @Test
    void list_invitationShowsWhoCreatedItAndKeepsUtcDates() {
        // Arrange
        UserEntity formerAdminCreator = newUser("Criador Anterior");
        addMember("Dono", CompanyRole.OWNER);
        invitations.add(CompanyInvitationEntity.builder()
                .id(UUID.randomUUID()).companyId(companyId).name("Ana").email("ana@example.com")
                .role(CompanyRole.VIEWER).status(InvitationStatus.PENDING).tokenHash("a".repeat(64))
                .tokenCiphertext("cipher").tokenKeyVersion("v1").createdBy(formerAdminCreator.getId())
                .createdAt(now.withOffsetSameInstant(ZoneOffset.ofHours(-3))).updatedAt(now).build());

        // Act
        MemberListResponse response = service.list(companyId, 0, 20);

        // Assert
        var invitation = response.items().get(1).invitation();
        assertThat(invitation.createdBy().name()).isEqualTo("Criador Anterior");
        assertThat(invitation.createdAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(response.items().get(1).member()).isNull();
        assertThat(response.items().get(0).invitation()).isNull();
    }

    @Test
    void list_acceptsMinimumAndMaximumPageSizeBoundaries() {
        addMember("Dono", CompanyRole.OWNER);

        MemberListResponse minSizeResponse = service.list(companyId, 0, 1);
        MemberListResponse maxSizeResponse = service.list(companyId, 0, 100);

        assertThat(minSizeResponse.size()).isEqualTo(1);
        assertThat(minSizeResponse.items()).hasSize(1);
        assertThat(maxSizeResponse.size()).isEqualTo(100);
        assertThat(maxSizeResponse.items()).hasSize(1);
    }

    @Test
    void list_invalidPageOrSize_isRejectedBeforeReadingAnything() {
        // Act / Assert
        assertThatThrownBy(() -> service.list(companyId, -1, 20)).isInstanceOf(InvalidParameterException.class);
        assertThatThrownBy(() -> service.list(companyId, 0, 0)).isInstanceOf(InvalidParameterException.class);
        assertThatThrownBy(() -> service.list(companyId, 0, 101)).isInstanceOf(InvalidParameterException.class);
        verifyNoInteractions(membershipRepository, invitationRepository, userRepository);
    }

    private static List<String> names(MemberListResponse response) {
        return response.items().stream()
                .map(item -> item.member() != null ? item.member().name() : item.invitation().name())
                .toList();
    }

    private UserEntity newUser(String name) {
        UserEntity user = UserEntity.builder().id(UUID.randomUUID()).companyId(companyId)
                .fullName(name).email(name.toLowerCase().replace(' ', '.') + "@example.com").build();
        users.add(user);
        return user;
    }

    private void addMember(String name, CompanyRole role) {
        UserEntity user = newUser(name);
        memberships.add(CompanyMembershipEntity.builder()
                .id(UUID.randomUUID()).companyId(companyId).userId(user.getId())
                .role(role).status(MembershipStatus.ACTIVE).joinedAt(now).updatedAt(now).build());
    }

    private void addInvitation(String name, CompanyRole role) {
        UserEntity creator = users.isEmpty() ? newUser("Dono") : users.get(0);
        invitations.add(CompanyInvitationEntity.builder()
                .id(UUID.randomUUID()).companyId(companyId).name(name).email(name.toLowerCase() + "@example.com")
                .role(role).status(InvitationStatus.PENDING).tokenHash("a".repeat(64))
                .tokenCiphertext("cipher").tokenKeyVersion("v1").createdBy(creator.getId())
                .createdAt(now.withOffsetSameInstant(ZoneOffset.ofHours(-3))).updatedAt(now).build());
    }
}
