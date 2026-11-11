package ages.vstable.backend.controller;

import ages.vstable.backend.dto.membership.MemberListResponse;
import ages.vstable.backend.dto.membership.MemberResponse;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.MembershipStatus;
import ages.vstable.backend.exception.ForbiddenException;
import ages.vstable.backend.service.CompanyMemberService;
import ages.vstable.backend.support.TestCompanyUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompanyMemberControllerTest {

    private final UUID companyId = UUID.randomUUID();

    @Mock
    private CompanyMemberService memberService;

    private CompanyMemberController controller;

    @BeforeEach
    void setUp() {
        controller = new CompanyMemberController(memberService);
    }

    private static void assertForbiddenWith(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ForbiddenException.class, ex -> assertThat(ex.getCode()).isEqualTo(code));
    }

    private UserEntity named(UserEntity user) {
        user.setFullName("Maria Souza");
        user.setEmail("maria@agrosul.com.br");
        return user;
    }

    @Test
    void list_owner_returnsThePageOfTheService() {
        // Arrange
        UserEntity owner = TestCompanyUsers.member(companyId, CompanyRole.OWNER);
        MemberListResponse page = new MemberListResponse(
                List.of(), 1, 5, 0, 0, new MemberListResponse.Summary(0, 0, 0));
        when(memberService.list(companyId, 1, 5)).thenReturn(page);

        // Act
        ResponseEntity<MemberListResponse> response = controller.list(companyId, 1, 5, owner);

        // Assert
        assertThat(response.getBody()).isSameAs(page);
    }

    @ParameterizedTest
    @EnumSource(value = CompanyRole.class, names = {"OPERATOR", "VIEWER"})
    void list_ordinaryMembers_areToldThatTheOwnerIsRequiredAndNothingIsRead(CompanyRole role) {
        // Arrange
        UserEntity member = TestCompanyUsers.member(companyId, role);

        // Act / Assert
        assertForbiddenWith(() -> controller.list(companyId, 0, 20, member), "OWNER_REQUIRED");
        verifyNoInteractions(memberService);
    }

    @Test
    void list_removedOwnerOtherCompanyAndAdministrator_areRefusedAndNothingIsRead() {
        // Act / Assert
        assertForbiddenWith(() -> controller.list(
                companyId, 0, 20, TestCompanyUsers.removedMember(companyId, CompanyRole.OWNER)),
                "COMPANY_ACCESS_REVOKED");
        assertForbiddenWith(() -> controller.list(
                companyId, 0, 20, TestCompanyUsers.member(UUID.randomUUID(), CompanyRole.OWNER)),
                "COMPANY_ACCESS_DENIED");
        assertForbiddenWith(() -> controller.list(companyId, 0, 20, null), "COMPANY_ACCESS_DENIED");
        verifyNoInteractions(memberService);
    }

    @ParameterizedTest
    @EnumSource(CompanyRole.class)
    void me_everyActiveProfile_getsItsOwnMembershipWithTheVersionAsETag(CompanyRole role) {
        // Arrange
        UserEntity user = named(TestCompanyUsers.member(companyId, role));

        // Act
        ResponseEntity<MemberResponse> response = controller.me(companyId, user);

        // Assert
        MemberResponse member = response.getBody();
        assertThat(member).isNotNull();
        assertThat(member.id()).isEqualTo(user.getMembership().getId());
        assertThat(member.userId()).isEqualTo(user.getId());
        assertThat(member.name()).isEqualTo("Maria Souza");
        assertThat(member.email()).isEqualTo("maria@agrosul.com.br");
        assertThat(member.role()).isEqualTo(role);
        assertThat(member.status()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"1\"");
        verifyNoInteractions(memberService);
    }

    @Test
    void me_removedMember_stillSeesItsOwnMembershipAsRemoved() {
        // Arrange
        UserEntity user = named(TestCompanyUsers.removedMember(companyId, CompanyRole.OPERATOR));

        // Act
        MemberResponse member = controller.me(companyId, user).getBody();

        // Assert
        assertThat(member).isNotNull();
        assertThat(member.status()).isEqualTo(MembershipStatus.REMOVED);
        assertThat(member.removedAt()).isNotNull();
    }

    @Test
    void me_otherCompanyNoMembershipAndAdministrator_areDenied() {
        // Act / Assert
        assertForbiddenWith(() -> controller.me(
                UUID.randomUUID(), TestCompanyUsers.member(companyId, CompanyRole.OWNER)), "COMPANY_ACCESS_DENIED");
        assertForbiddenWith(() -> controller.me(
                companyId, UserEntity.builder().id(UUID.randomUUID()).companyId(companyId).build()),
                "COMPANY_ACCESS_DENIED");
        assertForbiddenWith(() -> controller.me(companyId, null), "COMPANY_ACCESS_DENIED");
    }
}
