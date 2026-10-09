package ages.vstable.backend.exception;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlindPayIntegrationExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    static Stream<Arguments> blindPayFailures() {
        return Stream.of(
                Arguments.of("business rule rejected by BlindPay",
                        BlindPayIntegrationException.response("insufficient balance", 400,
                                "PAYOUTS_INSUFFICIENT_BALANCE", "trace", null),
                        HttpStatus.UNPROCESSABLE_CONTENT),
                Arguments.of("customer KYC not approved",
                        BlindPayIntegrationException.response("kyc pending", 403,
                                "CUSTOMERS_KYC_NOT_APPROVED", "trace", null),
                        HttpStatus.UNPROCESSABLE_CONTENT),
                Arguments.of("resource not found at BlindPay",
                        BlindPayIntegrationException.response("not found", 404,
                                "PAYOUTS_NOT_FOUND", "trace", null),
                        HttpStatus.UNPROCESSABLE_CONTENT),
                Arguments.of("rate limited",
                        BlindPayIntegrationException.response("slow down", 429,
                                "AUTH_RATE_LIMITED", "trace", null),
                        HttpStatus.SERVICE_UNAVAILABLE),
                Arguments.of("provider unavailable",
                        BlindPayIntegrationException.response("down", 503, null, null, null),
                        HttpStatus.SERVICE_UNAVAILABLE),
                Arguments.of("retryable code returned as 4xx",
                        BlindPayIntegrationException.response("wait allowance", 400,
                                "PAYOUTS_ALLOWANCE_NOT_CONFIRMED", "trace", null),
                        HttpStatus.SERVICE_UNAVAILABLE),
                Arguments.of("network failure",
                        BlindPayIntegrationException.communication("unreachable", null),
                        HttpStatus.SERVICE_UNAVAILABLE),
                Arguments.of("invalid API key",
                        BlindPayIntegrationException.response("unauthorized", 401,
                                "AUTH_UNAUTHORIZED", "trace", null),
                        HttpStatus.BAD_GATEWAY),
                Arguments.of("API key without permission",
                        BlindPayIntegrationException.response("forbidden", 403,
                                "AUTH_FORBIDDEN", "trace", null),
                        HttpStatus.BAD_GATEWAY),
                Arguments.of("missing configuration",
                        BlindPayIntegrationException.configuration("BLINDPAY_API_KEY is not configured", null),
                        HttpStatus.BAD_GATEWAY),
                Arguments.of("unreadable provider response",
                        BlindPayIntegrationException.invalidResponse("unreadable"),
                        HttpStatus.BAD_GATEWAY));
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("blindPayFailures")
    void mapsBlindPayFailureToPredictableHttpStatus(
            String scenario, BlindPayIntegrationException exception, HttpStatus expectedStatus) {
        var response = handler.handleBlindPayIntegration(exception);

        assertEquals(expectedStatus, response.getStatusCode());
        assertEquals(exception.getMessage(), response.getBody().get("message"));
    }
}
