package ages.vstable.backend.exception;

public class NotFoundException extends RuntimeException {

    private static final String DEFAULT_CODE = "NOT_FOUND";

    private final String code;

    public NotFoundException(String message) {
        this(DEFAULT_CODE, message);
    }

    public NotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
