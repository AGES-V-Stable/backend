package ages.vstable.backend.dto.membership;

import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.InvitationStatus;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratedInvitationResponseTest {

    @Test
    void toString_neverPrintsTheInviteLinkNorThePersonalDataOfTheInvitation() {
        // Arrange
        InvitationResponse invitation = new InvitationResponse(UUID.randomUUID(), UUID.randomUUID(), "Mariana Costa",
                "mariana@agrosul.com.br", CompanyRole.OPERATOR, InvitationStatus.PENDING, 1,
                new InvitationResponse.CreatedBy(UUID.randomUUID(), "João Rangel"),
                OffsetDateTime.now(ZoneOffset.UTC), null, null);
        GeneratedInvitationResponse generated = new GeneratedInvitationResponse(
                invitation, "https://frontend.example/convites/empresa#token=segredo-do-convite");

        // Act
        String text = generated.toString();

        // Assert
        assertThat(text).doesNotContain("segredo-do-convite").doesNotContain("frontend.example");
        assertThat(text).doesNotContain("Mariana Costa").doesNotContain("mariana@agrosul.com.br");
        assertThat(text).contains(invitation.id().toString());
    }
}
