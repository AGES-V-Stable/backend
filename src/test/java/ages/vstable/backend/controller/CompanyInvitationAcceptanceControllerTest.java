package ages.vstable.backend.controller;

import ages.vstable.backend.dto.membership.InvitationAcceptRequest;
import ages.vstable.backend.dto.membership.InvitationAcceptanceMode;
import ages.vstable.backend.dto.membership.InvitationNewUserAcceptRequest;
import ages.vstable.backend.dto.membership.InvitationNewUserAcceptResponse;
import ages.vstable.backend.dto.membership.InvitationResolveResponse;
import ages.vstable.backend.dto.membership.InvitationTokenRequest;
import ages.vstable.backend.dto.membership.MemberResponse;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.MembershipStatus;
import ages.vstable.backend.exception.ForbiddenException;
import ages.vstable.backend.service.CompanyInvitationAcceptanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompanyInvitationAcceptanceControllerTest {

    private static final String TOKEN = "A".repeat(43);

    @Mock
    private CompanyInvitationAcceptanceService acceptanceService;

    private CompanyInvitationAcceptanceController controller;

    @BeforeEach
    void setUp() {
        controller = new CompanyInvitationAcceptanceController(acceptanceService);
    }

    @Test
    void resolve_returnsTheInvitationWithNoStore() {
        // Arrange
        InvitationResolveResponse resolved = new InvitationResolveResponse(UUID.randomUUID(),
                new InvitationResolveResponse.Company(UUID.randomUUID(), "AgroSul"),
                new InvitationResolveResponse.InvitedBy("João"),
                new InvitationResolveResponse.Recipient("Mariana", "mariana@agrosul.com.br"),
                CompanyRole.VIEWER, InvitationAcceptanceMode.REGISTER,
                new InvitationResolveResponse.Terms("v1", "https://t", "https://p"));
        when(acceptanceService.resolve(TOKEN)).thenReturn(resolved);

        // Act
        ResponseEntity<InvitationResolveResponse> response = controller.resolve(new InvitationTokenRequest(TOKEN));

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(resolved);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    }

    @Test
    void acceptNewUser_returns201WithNoStoreAndNoAuthorizationHeader() {
        // Arrange
        InvitationNewUserAcceptRequest request = new InvitationNewUserAcceptRequest(TOKEN, "Senha@123", "Senha@123",
                true, "v1");
        InvitationNewUserAcceptResponse created = new InvitationNewUserAcceptResponse(UUID.randomUUID(), member(1),
                "LOGIN");
        when(acceptanceService.acceptNewUser(request)).thenReturn(created);

        // Act
        ResponseEntity<InvitationNewUserAcceptResponse> response = controller.acceptNewUser(request);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isSameAs(created);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().containsHeader("Authorization")).isFalse();
    }

    @Test
    void accept_companyUser_returnsTheMembershipWithETagAndNoStore() {
        // Arrange
        UserEntity user = UserEntity.builder().id(UUID.randomUUID()).build();
        InvitationAcceptRequest request = new InvitationAcceptRequest(TOKEN, true, "v1");
        MemberResponse member = member(1);
        when(acceptanceService.accept(user, request)).thenReturn(member);

        // Act
        ResponseEntity<MemberResponse> response = controller.accept(request, user);

        // Assert
        assertThat(response.getBody()).isSameAs(member);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"1\"");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    }

    @Test
    void accept_withoutCompanyIdentity_isIdentityMismatchWithoutCallingTheService() {
        // Act / Assert: administradores chegam aqui sem UserEntity
        assertThatThrownBy(() -> controller.accept(new InvitationAcceptRequest(TOKEN, true, "v1"), null))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("INVITATION_IDENTITY_MISMATCH"));
        verifyNoInteractions(acceptanceService);
    }

    @Test
    void requests_toStringNeverExposesTokenOrPassword() {
        // Act / Assert
        assertThat(new InvitationTokenRequest(TOKEN).toString()).doesNotContain(TOKEN);
        assertThat(new InvitationAcceptRequest(TOKEN, true, "v1").toString()).doesNotContain(TOKEN);
        assertThat(new InvitationNewUserAcceptRequest(TOKEN, "Senha@123", "Senha@123", true, "v1").toString())
                .doesNotContain(TOKEN).doesNotContain("Senha@123");
    }

    private static MemberResponse member(long version) {
        return new MemberResponse(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Mariana",
                "mariana@agrosul.com.br", CompanyRole.VIEWER, MembershipStatus.ACTIVE, version,
                OffsetDateTime.now(ZoneOffset.UTC), null);
    }
}
