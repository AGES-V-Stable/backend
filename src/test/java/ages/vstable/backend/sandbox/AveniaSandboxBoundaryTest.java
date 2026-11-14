package ages.vstable.backend.sandbox;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AveniaSandboxBoundaryTest {
    @Test
    void errorClassificationNeverMistakesAnIdentifierInTheUrlForAValidationFailure() {
        assertThat(AveniaSandboxSession.validationField(new RuntimeException(
                "HTTP 500 on /v2/account/metadata?subAccountId=synthetic-private-reference"))).isNull();
        assertThat(AveniaSandboxSession.validationField(new RuntimeException(
                "HTTP 400 - InvalidFieldError: isSameEconomicGroup is invalid"))).isEqualTo("isSameEconomicGroup");
        assertThat(AveniaSandboxSession.validationField(new RuntimeException(
                "InvalidFieldError: syntheticPrivateField is invalid"))).isNull();
    }

    @Test
    void permissionExtractionExportsOnlyAllowedBooleanFlags() {
        var root = JsonMapper.builder().build().readTree("""
                {"email":"synthetic@example.test","token":"synthetic-never-export",
                 "access":{"accessPermission":{"admin":true,"viewOnly":false,"payIn":true,"id":"synthetic-id"}}}
                """);
        var flags = AveniaSandboxSession.permissionFlags(root);
        assertThat(flags).containsExactlyInAnyOrderEntriesOf(java.util.Map.of("admin", true, "viewOnly", false, "payIn", true));
    }

    @Test
    void rejectsProductionOtherProvidersAndMutationsOutsideAssessment() {
        for (String origin : new String[]{"https://api.avenia.io:8443", "https://api.blindpay.com", "http://api.sandbox.avenia.io:10952", "https://api.sandbox.avenia.io:10952.attacker.test"}) {
            assertThatThrownBy(() -> AveniaSandboxSession.validateTarget(URI.create(origin + "/v2/account/account-info"), HttpMethod.GET))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> AveniaSandboxSession.validateTarget(URI.create(AveniaSandboxSession.ORIGIN + "/v2/notifications/webhooks/"), HttpMethod.POST))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AveniaSandboxSession.validateTarget(URI.create(AveniaSandboxSession.ORIGIN + "/v2/account/sub-accounts"), HttpMethod.DELETE))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> AveniaSandboxSession.validateTarget(URI.create(AveniaSandboxSession.ORIGIN + "/v2/account/account-info"), HttpMethod.GET)).doesNotThrowAnyException();
    }
}
