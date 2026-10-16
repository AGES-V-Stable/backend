package ages.vstable.backend.entity.enums;

/** Espelha {@code transaction_status_enum} do schema (ver V1__init.sql). */
public enum TransactionStatus {
    AWAITING_PAYMENT,
    PROCESSING,
    HELD,
    SETTLED,
    FAILED,
    PARTIAL_FAILURE,
    CANCELED,
    EXPIRED
}
