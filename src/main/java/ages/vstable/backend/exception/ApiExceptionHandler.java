package ages.vstable.backend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(DocumentoInvalidoException.class)
    public ProblemDetail handleDocumentoInvalido(DocumentoInvalidoException exception) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Documento inválido",
                exception.getMessage()
        );
    }

    @ExceptionHandler(EmpresaNaoEncontradaException.class)
    public ProblemDetail handleEmpresaNaoEncontrada(EmpresaNaoEncontradaException exception) {
        return problem(
                HttpStatus.NOT_FOUND,
                "Empresa não encontrada",
                exception.getMessage()
        );
    }

    @ExceptionHandler(ArmazenamentoDocumentoException.class)
    public ProblemDetail handleArmazenamento(ArmazenamentoDocumentoException exception) {
        return problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Armazenamento indisponível",
                exception.getMessage()
        );
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleMaxUploadSize(MaxUploadSizeExceededException exception) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Documento inválido",
                "O arquivo deve ter no máximo 10 MB"
        );
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
