package ages.vstable.backend.controller;

import ages.vstable.backend.dto.membership.GeneratedInvitationResponse;
import ages.vstable.backend.dto.membership.InvitationCreateRequest;
import ages.vstable.backend.dto.membership.InvitationResponse;
import ages.vstable.backend.dto.membership.InviteRole;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.InvitationStatus;
import ages.vstable.backend.exception.ForbiddenException;
import ages.vstable.backend.exception.InvalidParameterException;
import ages.vstable.backend.exception.PreconditionRequiredException;
import ages.vstable.backend.service.CompanyInvitationService;
import ages.vstable.backend.support.TestCompanyUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompanyInvitationControllerTest {

    private final UUID companyId = UUID.randomUUID();
    private final UUID invitationId = UUID.randomUUID();

    @Mock
    private CompanyInvitationService invitationService;

    private CompanyInvitationController controller;

    @BeforeEach
    void setUp() {
        controller = new CompanyInvitationController(invitationService);
    }

    private InvitationResponse invitation(long version) {
        return new InvitationResponse(invitationId, companyId, "Mariana Costa", "mariana@agrosul.com.br",
                CompanyRole.OPERATOR, InvitationStatus.PENDING, version,
                new InvitationResponse.CreatedBy(UUID.randomUUID(), "João Rangel"),
                OffsetDateTime.now(ZoneOffset.UTC), null, null);
    }

    private static void assertForbiddenWith(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ForbiddenException.class, ex -> assertThat(ex.getCode()).isEqualTo(code));
    }

    @Test
    void find_owner_returnsTheMetadataWithTheVersionAsETag() {
        // Arrange
        UserEntity owner = TestCompanyUsers.member(companyId, CompanyRole.OWNER);
        when(invitationService.find(companyId, invitationId)).thenReturn(invitation(3));

        // Act
        ResponseEntity<InvitationResponse> response = controller.find(companyId, invitationId, owner);

        // Assert
        assertThat(response.getBody().id()).isEqualTo(invitationId);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"3\"");
    }

    @Test
    void findLink_owner_returnsTheLinkWithETagAndNoStore() {
        // Arrange
        UserEntity owner = TestCompanyUsers.member(companyId, CompanyRole.OWNER);
        GeneratedInvitationResponse generated = new GeneratedInvitationResponse(
                invitation(2), "https://frontend.example/convites/empresa#token=abc");
        when(invitationService.findLink(companyId, invitationId)).thenReturn(generated);

        // Act
        ResponseEntity<GeneratedInvitationResponse> response = controller.findLink(companyId, invitationId, owner);

        // Assert
        assertThat(response.getBody()).isSameAs(generated);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"2\"");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    }

    @ParameterizedTest
    @EnumSource(value = CompanyRole.class, names = {"OPERATOR", "VIEWER"})
    void invitations_ordinaryMembers_cannotReadOthersInvitationsNorTheirLinks(CompanyRole role) {
        // Arrange
        UserEntity member = TestCompanyUsers.member(companyId, role);

        // Act / Assert
        assertForbiddenWith(() -> controller.find(companyId, invitationId, member), "OWNER_REQUIRED");
        assertForbiddenWith(() -> controller.findLink(companyId, invitationId, member), "OWNER_REQUIRED");
        verifyNoInteractions(invitationService);
    }

    @Test
    void invitations_removedOwnerOtherCompanyAndAdministrator_areRefusedAndNothingIsRead() {
        // Act / Assert
        UserEntity removedOwner = TestCompanyUsers.removedMember(companyId, CompanyRole.OWNER);
        UserEntity ownerOfAnotherCompany = TestCompanyUsers.member(UUID.randomUUID(), CompanyRole.OWNER);

        assertForbiddenWith(() -> controller.findLink(companyId, invitationId, removedOwner),
                "COMPANY_ACCESS_REVOKED");
        assertForbiddenWith(() -> controller.findLink(companyId, invitationId, ownerOfAnotherCompany),
                "COMPANY_ACCESS_DENIED");
        assertForbiddenWith(() -> controller.find(companyId, invitationId, null), "COMPANY_ACCESS_DENIED");
        verifyNoInteractions(invitationService);
    }

    private GeneratedInvitationResponse generated(long version) {
        return new GeneratedInvitationResponse(invitation(version), "https://frontend.example/convites/empresa#token=abc");
    }

    private static InvitationCreateRequest createRequest() {
        return new InvitationCreateRequest("Mariana Costa", "mariana@agrosul.com.br", InviteRole.OPERATOR);
    }

    @Test
    void create_owner_returns201WithTheLinkETagAndNoStore() {
        // Arrange
        UserEntity owner = TestCompanyUsers.member(companyId, CompanyRole.OWNER);
        InvitationCreateRequest request = createRequest();
        GeneratedInvitationResponse generated = generated(1);
        when(invitationService.create(companyId, owner, request)).thenReturn(generated);

        // Act
        ResponseEntity<GeneratedInvitationResponse> response = controller.create(companyId, request, owner);

        // Assert
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isSameAs(generated);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"1\"");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    }

    @Test
    void regenerate_owner_passesTheVersionOfIfMatchAndReturnsTheNewVersionAsETag() {
        // Arrange
        UserEntity owner = TestCompanyUsers.member(companyId, CompanyRole.OWNER);
        GeneratedInvitationResponse generated = generated(4);
        when(invitationService.regenerate(companyId, invitationId, 3)).thenReturn(generated);

        // Act
        ResponseEntity<GeneratedInvitationResponse> response =
                controller.regenerate(companyId, invitationId, "\"3\"", owner);

        // Assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(generated);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"4\"");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    }

    @Test
    void revoke_owner_returns204AndPassesTheVersionOfIfMatch() {
        // Arrange
        UserEntity owner = TestCompanyUsers.member(companyId, CompanyRole.OWNER);

        // Act
        ResponseEntity<Void> response = controller.revoke(companyId, invitationId, "\"2\"", owner);

        // Assert
        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(response.getBody()).isNull();
        verify(invitationService).revoke(companyId, invitationId, 2);
    }

    @Test
    void regenerateAndRevoke_withoutIfMatch_arePreconditionRequiredAndNothingChanges() {
        // Arrange
        UserEntity owner = TestCompanyUsers.member(companyId, CompanyRole.OWNER);

        // Act / Assert
        assertThatThrownBy(() -> controller.regenerate(companyId, invitationId, null, owner))
                .isInstanceOfSatisfying(PreconditionRequiredException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("VERSION_REQUIRED"));
        assertThatThrownBy(() -> controller.revoke(companyId, invitationId, null, owner))
                .isInstanceOfSatisfying(PreconditionRequiredException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("VERSION_REQUIRED"));
        verifyNoInteractions(invitationService);
    }

    @Test
    void regenerateAndRevoke_ifMatchNotInTheETagFormat_isAnInvalidParameter() {
        // Arrange
        UserEntity owner = TestCompanyUsers.member(companyId, CompanyRole.OWNER);

        // Act / Assert
        assertThatThrownBy(() -> controller.regenerate(companyId, invitationId, "3", owner))
                .isInstanceOf(InvalidParameterException.class);
        assertThatThrownBy(() -> controller.revoke(companyId, invitationId, "*", owner))
                .isInstanceOf(InvalidParameterException.class);
        verifyNoInteractions(invitationService);
    }

    @ParameterizedTest
    @EnumSource(value = CompanyRole.class, names = {"OPERATOR", "VIEWER"})
    void create_regenerateAndRevoke_ordinaryMembers_areRefusedBeforeAnythingElse(CompanyRole role) {
        // Arrange
        UserEntity member = TestCompanyUsers.member(companyId, role);

        // Act / Assert: sem If-Match também é 403, a autorização vem antes da versão
        assertForbiddenWith(() -> controller.create(companyId, createRequest(), member), "OWNER_REQUIRED");
        assertForbiddenWith(() -> controller.regenerate(companyId, invitationId, null, member), "OWNER_REQUIRED");
        assertForbiddenWith(() -> controller.revoke(companyId, invitationId, "\"1\"", member), "OWNER_REQUIRED");
        verifyNoInteractions(invitationService);
    }

    @Test
    void create_regenerateAndRevoke_removedOwnerOtherCompanyAndAdministrator_areRefused() {
        // Arrange
        UserEntity removedOwner = TestCompanyUsers.removedMember(companyId, CompanyRole.OWNER);
        UserEntity ownerOfAnotherCompany = TestCompanyUsers.member(UUID.randomUUID(), CompanyRole.OWNER);

        // Act / Assert
        assertForbiddenWith(() -> controller.create(companyId, createRequest(), removedOwner), "COMPANY_ACCESS_REVOKED");
        assertForbiddenWith(() -> controller.regenerate(companyId, invitationId, "\"1\"", removedOwner),
                "COMPANY_ACCESS_REVOKED");
        assertForbiddenWith(() -> controller.revoke(companyId, invitationId, "\"1\"", removedOwner),
                "COMPANY_ACCESS_REVOKED");
        assertForbiddenWith(() -> controller.create(companyId, createRequest(), ownerOfAnotherCompany),
                "COMPANY_ACCESS_DENIED");
        assertForbiddenWith(() -> controller.regenerate(companyId, invitationId, "\"1\"", ownerOfAnotherCompany),
                "COMPANY_ACCESS_DENIED");
        assertForbiddenWith(() -> controller.revoke(companyId, invitationId, "\"1\"", null), "COMPANY_ACCESS_DENIED");
        verifyNoInteractions(invitationService);
    }
}
