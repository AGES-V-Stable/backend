package ages.vstable.backend.exception;

public class ForbiddenException extends RuntimeException {

    private static final String DEFAULT_CODE = "FORBIDDEN";

    private final String code;

    public ForbiddenException(String message) {
        this(DEFAULT_CODE, message);
    }

    public ForbiddenException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
