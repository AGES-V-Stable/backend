package ages.vstable.backend.exception;

import ages.vstable.backend.dto.onboarding.AccountRequestDTO;
import ages.vstable.backend.logging.OperationalLog;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("code", "INVALID_ARGUMENT", "message", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        // Conta avulsa e cadastro em uma etapa (que estende a conta) respondem igual ao e-mail inválido.
        boolean invalidOnboardingEmail = ex.getTarget() instanceof AccountRequestDTO
                && ex.getBindingResult().getFieldErrors("email").stream()
                        .anyMatch(error -> error.getCodes() != null
                                && java.util.Arrays.stream(error.getCodes())
                                        .anyMatch(code -> code.equals("Email") || code.startsWith("Email.")));
        if (invalidOnboardingEmail) {
            return ResponseEntity
                    .status(HttpStatus.UNPROCESSABLE_CONTENT)
                    .body(Map.of("code", "UNPROCESSABLE_CONTENT", "message", "Email has an invalid format"));
        }

        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("Invalid data");
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("code", "VALIDATION_ERROR", "message", message));
    }

    @ExceptionHandler(InvalidDateRangeException.class)
    public ResponseEntity<Map<String, String>> handleInvalidDateRange(InvalidDateRangeException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("code", "INVALID_DATE_RANGE", "message", ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleMalformedPayload(HttpMessageNotReadableException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("code", "MALFORMED_PAYLOAD", "message", "Malformed payload"));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, String>> handleMissingHeader(MissingRequestHeaderException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("code", "MISSING_HEADER", "message", ex.getMessage()));
    }

    @ExceptionHandler({ MissingServletRequestParameterException.class, MissingServletRequestPartException.class })
    public ResponseEntity<Map<String, String>> handleMissingRequestPart(Exception ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        return ResponseEntity
                .status(HttpStatus.CONTENT_TOO_LARGE)
                .body(Map.of("message", "File is too large"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("code", "INVALID_PARAMETER", "message", "Parameter '" + ex.getName() + "' has an invalid format"));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(ConflictException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", ex.getCode());
        body.put("message", ex.getMessage());
        if (!ex.getDetails().isEmpty()) {
            body.put("details", ex.getDetails());
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, String>> handleForbidden(ForbiddenException ex) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(Map.of("code", ex.getCode(), "message", ex.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NotFoundException ex) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", ex.getCode(), "message", ex.getMessage()));
    }

    @ExceptionHandler(GoneException.class)
    public ResponseEntity<Map<String, String>> handleGone(GoneException ex) {
        return ResponseEntity
                .status(HttpStatus.GONE)
                .body(Map.of("code", ex.getCode(), "message", ex.getMessage()));
    }

    @ExceptionHandler(PreconditionFailedException.class)
    public ResponseEntity<Map<String, String>> handlePreconditionFailed(PreconditionFailedException ex) {
        return ResponseEntity
                .status(HttpStatus.PRECONDITION_FAILED)
                .body(Map.of("code", ex.getCode(), "message", ex.getMessage()));
    }

    @ExceptionHandler(PreconditionRequiredException.class)
    public ResponseEntity<Map<String, String>> handlePreconditionRequired(PreconditionRequiredException ex) {
        return ResponseEntity
                .status(HttpStatus.PRECONDITION_REQUIRED)
                .body(Map.of("code", ex.getCode(), "message", ex.getMessage()));
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<Map<String, String>> handleTooManyRequests(TooManyRequestsException ex) {
        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(Map.of("code", ex.getCode(), "message", ex.getMessage()));
    }

    @ExceptionHandler(InvalidParameterException.class)
    public ResponseEntity<Map<String, String>> handleInvalidParameter(InvalidParameterException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("code", "INVALID_PARAMETER", "message", ex.getMessage()));
    }

    @ExceptionHandler(UnprocessableEntityException.class)
    public ResponseEntity<Map<String, String>> handleUnprocessableEntity(UnprocessableEntityException ex) {
        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(Map.of("code", ex.getCode(), "message", ex.getMessage()));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, String>> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("code", "INVALID_CREDENTIALS", "message", ex.getMessage()));
    }

    @ExceptionHandler(LockedException.class)
    public ResponseEntity<Map<String, String>> handleLocked(LockedException ex) {
        return ResponseEntity
                .status(HttpStatus.LOCKED)
                .body(Map.of("code", "ACCOUNT_LOCKED", "message", ex.getMessage()));
    }

    @ExceptionHandler(AveniaIntegrationException.class)
    public ResponseEntity<Map<String, String>> handleAveniaIntegration(AveniaIntegrationException ex) {
        HttpStatus status;
        if (ex.isRetryable()) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
        } else if (ex.getProviderStatus() != null
                && (ex.getProviderStatus() == 400
                        || ex.getProviderStatus() == 404
                        || ex.getProviderStatus() == 409
                        || ex.getProviderStatus() == 422)) {
            status = HttpStatus.UNPROCESSABLE_CONTENT;
        } else {
            status = HttpStatus.BAD_GATEWAY;
        }

        return ResponseEntity
                .status(status)
                .body(Map.of("code", "PROVIDER_ERROR", "message", ex.getMessage()));
    }

    @ExceptionHandler(BlindPayIntegrationException.class)
    public ResponseEntity<Map<String, String>> handleBlindPayIntegration(BlindPayIntegrationException ex) {
        HttpStatus status;
        if (ex.isRetryable()) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
        } else if (ex.isRejectedByProvider()) {
            status = HttpStatus.UNPROCESSABLE_CONTENT;
        } else {
            status = HttpStatus.BAD_GATEWAY;
        }

        return ResponseEntity
                .status(status)
                .body(Map.of("code", "PROVIDER_ERROR", "message", ex.getMessage()));
    }

    @ExceptionHandler({ AccessDeniedException.class, AuthorizationDeniedException.class })
    public ResponseEntity<Map<String, String>> handleAccessDenied(Exception ex) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(Map.of("code", "ACCESS_DENIED", "message", "Access denied"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception ex) {
        OperationalLog.unexpected(ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("code", "INTERNAL_ERROR", "message", "Internal error"));
    }
}