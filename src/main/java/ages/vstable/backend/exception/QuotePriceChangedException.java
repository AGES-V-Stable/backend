package ages.vstable.backend.exception;

/**
 * O preço obtido no momento da execução é pior do que o confirmado pelo usuário. Nada é
 * executado: o usuário precisa pedir uma nova cotação e confirmá-la. Responde 409.
 */
public class QuotePriceChangedException extends ConflictException {
    public QuotePriceChangedException(String message) {
        super(message);
    }
}
