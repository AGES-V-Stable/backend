package ages.vstable.backend.exception;

import java.util.UUID;

public class EmpresaNotFoundException extends RuntimeException {

    public EmpresaNotFoundException(UUID id) {
        super("Empresa não encontrada: " + id);
    }
}
