package ages.vstable.backend.entity.enums;

/**
 * Sentido da transferência do ponto de vista da empresa cliente:
 * PAYMENT = importação (empresa paga um beneficiário no exterior);
 * RECEIPT = exportação (empresa recebe de um pagador no exterior).
 */
public enum TransferDirection {
    PAYMENT,
    RECEIPT
}
