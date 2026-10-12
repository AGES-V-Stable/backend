package ages.vstable.backend.exception;

import org.springframework.http.HttpStatus;

/**
 * Erro de negócio dos endpoints de detalhes e comprovante de transferência. Carrega o código
 * estável ({@code code}) devolvido ao frontend junto da mensagem.
 */
public class TransferDetailsException extends RuntimeException {

    public static final String INVALID_TRANSFER_ID = "INVALID_TRANSFER_ID";
    public static final String USER_NOT_VERIFIED = "USER_NOT_VERIFIED";
    public static final String COMPANY_ACCESS_REQUIRED = "COMPANY_ACCESS_REQUIRED";
    public static final String TRANSFER_NOT_FOUND = "TRANSFER_NOT_FOUND";
    public static final String RECEIPT_UNAVAILABLE = "RECEIPT_UNAVAILABLE";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private final HttpStatus status;
    private final String code;

    private TransferDetailsException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static TransferDetailsException invalidTransferId() {
        return new TransferDetailsException(HttpStatus.BAD_REQUEST, INVALID_TRANSFER_ID,
                "Identificador de transferência inválido.");
    }

    public static TransferDetailsException userNotVerified() {
        return new TransferDetailsException(HttpStatus.FORBIDDEN, USER_NOT_VERIFIED,
                "O usuário não possui a verificação exigida para consultar transferências.");
    }

    public static TransferDetailsException companyAccessRequired() {
        return new TransferDetailsException(HttpStatus.FORBIDDEN, COMPANY_ACCESS_REQUIRED,
                "O usuário não possui vínculo empresarial para consultar transferências.");
    }

    public static TransferDetailsException transferNotFound() {
        return new TransferDetailsException(HttpStatus.NOT_FOUND, TRANSFER_NOT_FOUND,
                "Transferência não encontrada.");
    }

    public static TransferDetailsException receiptUnavailable() {
        return new TransferDetailsException(HttpStatus.CONFLICT, RECEIPT_UNAVAILABLE,
                "O comprovante desta transferência ainda não está disponível.");
    }

    public static TransferDetailsException internalError() {
        return new TransferDetailsException(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR,
                "Erro interno inesperado.");
    }
}
