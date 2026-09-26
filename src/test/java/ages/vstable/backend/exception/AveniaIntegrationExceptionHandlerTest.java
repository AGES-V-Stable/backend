package ages.vstable.backend.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AveniaIntegrationExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsProviderValidationErrorsToUnprocessableEntity() {
        var response = handler.handleAveniaIntegration(
                AveniaIntegrationException.response("invalid quote", 400, null));

        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, response.getStatusCode());
        assertEquals("invalid quote", response.getBody().get("message"));
    }

    @Test
    void mapsRetryableErrorsToServiceUnavailable() {
        var response = handler.handleAveniaIntegration(
                AveniaIntegrationException.response("temporarily unavailable", 503, null));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    }

    @Test
    void mapsAuthenticationAndConfigurationErrorsToBadGateway() {
        var response = handler.handleAveniaIntegration(
                AveniaIntegrationException.response("provider authentication failed", 401, null));

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
    }
}
