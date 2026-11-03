package ages.vstable.backend.exception;

/** Período de consulta com data inicial posterior à data final. */
public class InvalidDateRangeException extends RuntimeException {
    public InvalidDateRangeException() {
        super("startDate must not be after endDate");
    }
}
