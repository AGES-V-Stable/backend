package ages.vstable.backend.exception;

/** Dados bem formados que violam uma regra (HTTP 422); {@code code} identifica a regra para o cliente. */
public class UnprocessableEntityException extends RuntimeException {

    private static final String DEFAULT_CODE = "UNPROCESSABLE_CONTENT";

    private final String code;

    public UnprocessableEntityException(String message) {
        this(DEFAULT_CODE, message);
    }

    public UnprocessableEntityException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
