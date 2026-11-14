package ages.vstable.backend.exception;

/** A requisição exige uma condição que o cliente não enviou (HTTP 428), como a versão do recurso em If-Match. */
public class PreconditionRequiredException extends RuntimeException {

    private final String code;

    public PreconditionRequiredException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
