package ages.vstable.backend.entity.enums;

/**
 * Estados da execução de uma oferta confirmada. Os estados terminais nunca são
 * deixados; a conclusão só é registrada depois da confirmação do provedor.
 */
public enum TransferExecutionStatus {
    /** Execução registrada; a chamada ao provedor ainda não teve resultado conhecido. */
    PENDING_SUBMISSION,
    /** Payin BlindPay criado; aguardando o pagamento (PIX/TED) do usuário. */
    AWAITING_FUNDS,
    /** Payin concluído: as stablecoins estão na carteira gerenciada, payout ainda não enviado. */
    FUNDS_RECEIVED,
    /** O preço do payout piorou durante o financiamento; o usuário precisa confirmar de novo. */
    REQUOTE_REQUIRED,
    /** Payout reservado para envio; a chamada ao provedor ainda não teve resultado conhecido. */
    PAYOUT_SUBMITTING,
    /** Payout aceito pela BlindPay; aguardando conclusão. */
    PAYOUT_PROCESSING,
    /** Ticket criado na Avenia; o andamento segue o status da transação. */
    SUBMITTED,
    COMPLETED,
    FAILED,
    REFUNDED,
    /** Resultado incerto que não pode ser repetido com segurança; exige conciliação manual. */
    RECONCILIATION_REQUIRED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == REFUNDED;
    }
}
