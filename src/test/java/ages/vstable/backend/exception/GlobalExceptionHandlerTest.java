package ages.vstable.backend.exception;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleIllegalArgument_returns400WithExceptionMessage() {
        IllegalArgumentException ex = new IllegalArgumentException("Invalid argument");

        ResponseEntity<Map<String, String>> response = handler.handleIllegalArgument(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("message", "Invalid argument");
    }

    @Test
    void handleMalformedPayload_returns400WithFixedMessage() {
        HttpMessageNotReadableException ex = mock(HttpMessageNotReadableException.class);

        ResponseEntity<Map<String, String>> response = handler.handleMalformedPayload(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("message", "Malformed payload");
    }

    @Test
    void handleMissingHeader_returns400WithExceptionMessage() {
        MissingRequestHeaderException ex = mock(MissingRequestHeaderException.class);
        when(ex.getMessage()).thenReturn("Required request header 'Authorization' is not present");

        ResponseEntity<Map<String, String>> response = handler.handleMissingHeader(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .containsEntry("message", "Required request header 'Authorization' is not present");
    }

    @Test
    void handleTypeMismatch_returns400WithParameterName() {
        MethodArgumentTypeMismatchException ex = mock(MethodArgumentTypeMismatchException.class);
        when(ex.getName()).thenReturn("id");

        ResponseEntity<Map<String, String>> response = handler.handleTypeMismatch(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("message", "Parameter 'id' has an invalid format");
    }

    @Test
    void handleConflict_returns409() {
        ConflictException ex = new ConflictException("Conflict");
        ResponseEntity<Map<String, String>> response = handler.handleConflict(ex);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("message", "Conflict");
    }

    @Test
    void handleNotFound_returns404() {
        NotFoundException ex = new NotFoundException("Not found");
        ResponseEntity<Map<String, String>> response = handler.handleNotFound(ex);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("message", "Not found");
    }

    @Test
    void handleForbidden_returns403() {
        ForbiddenException ex = new ForbiddenException("Forbidden");
        ResponseEntity<Map<String, String>> response = handler.handleForbidden(ex);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("message", "Forbidden");
    }

    @Test
    void handleUnprocessableEntity_returns422() {
        UnprocessableEntityException ex = new UnprocessableEntityException("Unprocessable");
        ResponseEntity<Map<String, String>> response = handler.handleUnprocessableEntity(ex);
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody()).containsEntry("message", "Unprocessable");
    }

    @Test
    void handleAccessDenied_returns403() {
        AccessDeniedException ex = new AccessDeniedException("Denied");
        ResponseEntity<Map<String, String>> response = handler.handleAccessDenied(ex);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("message", "Access denied");
    }

    @Test
    void handleAveniaIntegration_providerRejectsAsBusinessRule_returns422WithMessage() {
        AveniaIntegrationException ex = AveniaIntegrationException.response(
                "Falha ao finalizar KYC na Avenia: HTTP 400 - cannot repeat taxId across multiple users",
                400, new RuntimeException());

        ResponseEntity<Map<String, String>> response = handler.handleAveniaIntegration(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody()).containsEntry("message", ex.getMessage());
    }

    @Test
    void handleAveniaIntegration_providerStatusNotWhitelistedAndNotRetryable_returns502() {
        AveniaIntegrationException ex = AveniaIntegrationException.response(
                "Falha ao finalizar KYC na Avenia", 403, new RuntimeException());

        ResponseEntity<Map<String, String>> response = handler.handleAveniaIntegration(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void handleAveniaIntegration_retryableFailure_returns503() {
        AveniaIntegrationException ex = AveniaIntegrationException.communication(
                "Falha ao comunicar com a Avenia", new RuntimeException());

        ResponseEntity<Map<String, String>> response = handler.handleAveniaIntegration(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("message", ex.getMessage());
    }

    @Test
    void handleAveniaIntegration_configurationError_returns502() {
        AveniaIntegrationException ex = AveniaIntegrationException.configuration(
                "AVENIA_API_KEY is not configured", null);

        ResponseEntity<Map<String, String>> response = handler.handleAveniaIntegration(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).containsEntry("message", ex.getMessage());
    }

    @Test
    void handleUnexpected_returns500() {
        Exception ex = new Exception("Error");
        ResponseEntity<Map<String, String>> response = handler.handleUnexpected(ex);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("message", "Internal error");
    }
}
