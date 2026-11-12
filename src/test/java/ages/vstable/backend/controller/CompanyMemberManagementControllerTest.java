package ages.vstable.backend.controller;

import ages.vstable.backend.dto.membership.InviteRole;
import ages.vstable.backend.dto.membership.MemberResponse;
import ages.vstable.backend.dto.membership.MemberRoleUpdateRequest;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.exception.ForbiddenException;
import ages.vstable.backend.exception.PreconditionRequiredException;
import ages.vstable.backend.service.CompanyMemberService;
import ages.vstable.backend.support.TestCompanyUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompanyMemberManagementControllerTest {
    @Mock CompanyMemberService service;
    private CompanyMemberController controller;
    private final UUID companyId = UUID.randomUUID();
    private final UUID memberId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        controller = new CompanyMemberController(service);
    }

    @Test
    void patch_ownerPassesIfMatchAndReturnsNewEtag() {
        UserEntity owner = TestCompanyUsers.member(companyId, CompanyRole.OWNER);
        var result = new MemberResponse(memberId, UUID.randomUUID(), companyId, "Nome", "user@example.com",
                CompanyRole.VIEWER, ages.vstable.backend.entity.enums.MembershipStatus.ACTIVE, 5, null, null);
        when(service.changeRole(companyId, memberId, 4, InviteRole.VIEWER, owner.getId()))
                .thenReturn(result);
        var response = controller.changeRole(companyId, memberId, "\"4\"",
                new MemberRoleUpdateRequest(InviteRole.VIEWER), owner);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"5\"");
    }

    @Test
    void delete_ownerPassesIfMatchAndReturns204() {
        UserEntity owner = TestCompanyUsers.member(companyId, CompanyRole.OWNER);
        var response = controller.remove(companyId, memberId, "\"3\"", owner);
        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(service).revoke(companyId, memberId, 3, owner.getId());
    }

    @Test
    void patch_nonOwnerIsRejectedBeforeAccessingRepository() {
        UserEntity operator = TestCompanyUsers.member(companyId, CompanyRole.OPERATOR);
        assertThatThrownBy(() -> controller.changeRole(companyId, memberId, "\"1\"",
                new MemberRoleUpdateRequest(InviteRole.VIEWER), operator))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(service);
    }

    @Test
    void delete_missingIfMatchIs428() {
        UserEntity owner = TestCompanyUsers.member(companyId, CompanyRole.OWNER);
        assertThatThrownBy(() -> controller.remove(companyId, memberId, null, owner))
                .isInstanceOf(PreconditionRequiredException.class);
        verifyNoInteractions(service);
    }
}
