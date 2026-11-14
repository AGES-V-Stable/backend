package ages.vstable.backend.exception;

/** A condição enviada pelo cliente não vale mais (HTTP 412), como uma versão desatualizada do recurso. */
public class PreconditionFailedException extends RuntimeException {

    private final String code;

    public PreconditionFailedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
