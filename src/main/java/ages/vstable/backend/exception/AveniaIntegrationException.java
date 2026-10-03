package ages.vstable.backend.exception;

import lombok.Getter;

@Getter
public class AveniaIntegrationException extends RuntimeException {

    private final Integer providerStatus;
    private final boolean retryable;

    public AveniaIntegrationException(String message, Integer providerStatus, boolean retryable, Throwable cause) {
        super(message, cause);
        this.providerStatus = providerStatus;
        this.retryable = retryable;
    }

    public static AveniaIntegrationException configuration(String message, Throwable cause) {
        return new AveniaIntegrationException(message, null, false, cause);
    }

    public static AveniaIntegrationException response(String message, int providerStatus, Throwable cause) {
        return new AveniaIntegrationException(message, providerStatus,
                providerStatus == 429 || providerStatus >= 500, cause);
    }

    public static AveniaIntegrationException communication(String message, Throwable cause) {
        return new AveniaIntegrationException(message, null, true, cause);
    }
}
