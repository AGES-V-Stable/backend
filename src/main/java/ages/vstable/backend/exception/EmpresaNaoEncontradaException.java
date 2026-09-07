package ages.vstable.backend.exception;

import java.util.UUID;

public class EmpresaNaoEncontradaException extends RuntimeException {

    public EmpresaNaoEncontradaException(UUID empresaId) {
        super("Empresa não encontrada: " + empresaId);
    }
}
