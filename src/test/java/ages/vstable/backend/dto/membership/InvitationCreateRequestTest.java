package ages.vstable.backend.dto.membership;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvitationCreateRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void startValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void stopValidator() {
        factory.close();
    }

    private boolean valid(String name, String email, InviteRole role) {
        return validator.validate(new InvitationCreateRequest(name, email, role)).isEmpty();
    }

    @Test
    void normalization_trimsTheNameAndLowercasesTheTrimmedEmailKeepingDotsAndPlus() {
        // Act
        InvitationCreateRequest request =
                new InvitationCreateRequest("  Mariana Costa ", "  Mariana.Costa+Vendas@AgroSul.com.BR ", InviteRole.OPERATOR);

        // Assert
        assertThat(request.name()).isEqualTo("Mariana Costa");
        assertThat(request.email()).isEqualTo("mariana.costa+vendas@agrosul.com.br");
    }

    @Test
    void validation_acceptsAValidInvitationForEachInvitableRole() {
        assertThat(valid("Mariana Costa", "mariana@agrosul.com.br", InviteRole.OPERATOR)).isTrue();
        assertThat(valid("Jo", "jo@agrosul.com.br", InviteRole.VIEWER)).isTrue();
    }

    @Test
    void validation_nameIsMeasuredAfterTrimming() {
        assertThat(valid(" J ", "jo@agrosul.com.br", InviteRole.VIEWER)).isFalse();
        assertThat(valid("   ", "jo@agrosul.com.br", InviteRole.VIEWER)).isFalse();
        assertThat(valid(null, "jo@agrosul.com.br", InviteRole.VIEWER)).isFalse();
        assertThat(valid("a".repeat(255), "jo@agrosul.com.br", InviteRole.VIEWER)).isTrue();
        assertThat(valid("a".repeat(256), "jo@agrosul.com.br", InviteRole.VIEWER)).isFalse();
    }

    @Test
    void validation_nameWithControlCharactersIsRefusedBecauseTheDatabaseCannotStoreThem() {
        assertThat(valid("Mari\u0000ana", "jo@agrosul.com.br", InviteRole.VIEWER)).isFalse();
        assertThat(valid("Mari\nana", "jo@agrosul.com.br", InviteRole.VIEWER)).isFalse();
        assertThat(valid("Mari\tana", "jo@agrosul.com.br", InviteRole.VIEWER)).isFalse();
        assertThat(valid("Mari\u0085ana", "jo@agrosul.com.br", InviteRole.VIEWER)).isFalse();
        assertThat(valid("José da Conceição", "jo@agrosul.com.br", InviteRole.VIEWER)).isTrue();
    }

    @Test
    void toString_neverPrintsTheNameNorTheEmail() {
        // Act
        String text = new InvitationCreateRequest("Mariana Costa", "mariana@agrosul.com.br", InviteRole.OPERATOR).toString();

        // Assert
        assertThat(text).doesNotContain("Mariana").doesNotContain("agrosul").contains("OPERATOR");
    }

    @Test
    void validation_emailMustBeWellFormedAndAtMost255Characters() {
        assertThat(valid("Mariana", "sem-arroba", InviteRole.OPERATOR)).isFalse();
        assertThat(valid("Mariana", "   ", InviteRole.OPERATOR)).isFalse();
        assertThat(valid("Mariana", null, InviteRole.OPERATOR)).isFalse();
        assertThat(valid("Mariana", "a".repeat(250) + "@x.com", InviteRole.OPERATOR)).isFalse();
    }

    @Test
    void validation_roleIsRequired() {
        assertThat(valid("Mariana", "mariana@agrosul.com.br", null)).isFalse();
    }

    @Test
    void json_ownerAsRoleAndUnknownFieldsAreRejectedInsteadOfIgnored() {
        // Arrange
        JsonMapper mapper = JsonMapper.builder().build();

        // Act / Assert
        assertThatThrownBy(() -> mapper.readValue(
                "{\"name\":\"Mariana\",\"email\":\"m@x.com\",\"role\":\"OWNER\"}", InvitationCreateRequest.class))
                .isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> mapper.readValue(
                "{\"name\":\"Mariana\",\"email\":\"m@x.com\",\"role\":\"VIEWER\",\"companyId\":\"x\"}",
                InvitationCreateRequest.class))
                .isInstanceOf(JacksonException.class);
        assertThat(mapper.readValue("{\"name\":\"Mariana\",\"email\":\"M@X.com\",\"role\":\"VIEWER\"}",
                InvitationCreateRequest.class).email()).isEqualTo("m@x.com");
    }
}
