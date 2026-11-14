package ages.vstable.backend.exception;

/** Parâmetro de requisição fora do permitido, como página ou tamanho inválidos. */
public class InvalidParameterException extends RuntimeException {
    public InvalidParameterException(String message) {
        super(message);
    }
}
