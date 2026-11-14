package ages.vstable.backend.exception;

import java.util.Map;

public class ConflictException extends RuntimeException {

    private static final String DEFAULT_CODE = "CONFLICT";

    private final String code;
    private final Map<String, Object> details;

    public ConflictException(String message) {
        this(DEFAULT_CODE, message);
    }

    public ConflictException(String code, String message) {
        this(code, message, Map.of());
    }

    /** {@code details} complementa o conflito para o cliente, como o id do convite que já existe. */
    public ConflictException(String code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public String getCode() {
        return code;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
