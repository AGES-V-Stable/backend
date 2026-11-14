package ages.vstable.backend.exception;

/** O recurso existiu e não está mais disponível (HTTP 410), como um convite revogado. */
public class GoneException extends RuntimeException {

    private final String code;

    public GoneException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
