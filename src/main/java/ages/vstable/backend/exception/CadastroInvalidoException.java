package ages.vstable.backend.exception;

import java.util.UUID;

public class CadastroInvalidoException extends RuntimeException {

    public CadastroInvalidoException(UUID id) {
        super("Cadastro da empresa possui situação inválida: " + id);
    }
}
