package ages.vstable.backend.exception;

/** Período de consulta inválido: início posterior ao fim ou data fora do intervalo aceito. */
public class InvalidDateRangeException extends RuntimeException {
    public InvalidDateRangeException() {
        this("startDate must not be after endDate");
    }

    public InvalidDateRangeException(String message) {
        super(message);
    }
}
