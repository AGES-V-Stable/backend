package ages.vstable.backend.exception;

import ages.vstable.backend.controller.TransferDetailsController;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Erros do {@link TransferDetailsController} no formato {@code {code, message}} do contrato.
 * Restrito a esse controller para não mudar o formato de erro dos demais endpoints.
 */
@Slf4j
@RestControllerAdvice(assignableTypes = TransferDetailsController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TransferDetailsExceptionHandler {

    @Schema(description = "Erro dos endpoints de detalhes e comprovante")
    public record ErrorBody(
            @Schema(example = "RECEIPT_UNAVAILABLE") String code,
            @Schema(example = "O comprovante desta transferência ainda não está disponível.") String message) {
    }

    @ExceptionHandler(TransferDetailsException.class)
    public ResponseEntity<ErrorBody> handleTransferDetails(TransferDetailsException ex) {
        return json(ex);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorBody> handleInvalidId() {
        return json(TransferDetailsException.invalidTransferId());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> handleUnexpected(Exception ex) {
        // Só o tipo, sem a mensagem: ela pode carregar dados da operação.
        log.error("Falha inesperada em detalhes/comprovante de transferência: {}", ex.getClass().getName());
        return json(TransferDetailsException.internalError());
    }

    /** Content-Type explícito: o download declara produces=application/pdf e o erro é JSON. */
    private static ResponseEntity<ErrorBody> json(TransferDetailsException ex) {
        return ResponseEntity.status(ex.getStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorBody(ex.getCode(), ex.getMessage()));
    }
}
