package ages.vstable.backend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(EmpresaNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleEmpresaNotFound(EmpresaNotFoundException ex) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(
                        HttpStatus.NOT_FOUND.value(),
                        "Not Found",
                        ex.getMessage()
                ));
    }

    @ExceptionHandler(CadastroInvalidoException.class)
    public ResponseEntity<ErrorResponse> handleCadastroInvalido(CadastroInvalidoException ex) {
        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(new ErrorResponse(
                        HttpStatus.UNPROCESSABLE_CONTENT.value(),
                        HttpStatus.UNPROCESSABLE_CONTENT.getReasonPhrase(),
                        ex.getMessage()
                ));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleInvalidPathParameter(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(
                        HttpStatus.BAD_REQUEST.value(),
                        "Bad Request",
                        "Parâmetro '" + ex.getName() + "' inválido"
                ));
    }
}
