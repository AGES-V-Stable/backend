package ages.vstable.backend.exception;

/** Limite de uso atingido (HTTP 429); {@code retryAfterSeconds} vai no cabeçalho Retry-After. */
public class TooManyRequestsException extends RuntimeException {

    private final String code;
    private final long retryAfterSeconds;

    public TooManyRequestsException(String code, String message, long retryAfterSeconds) {
        super(message);
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String getCode() {
        return code;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
