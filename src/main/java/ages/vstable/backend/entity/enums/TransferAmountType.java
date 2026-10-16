package ages.vstable.backend.entity.enums;

/**
 * Identifica qual lado da operação o valor informado pelo usuário representa —
 * necessário porque a cotação da Avenia é sempre pedida com exatamente um dos
 * dois lados (inputAmount XOR outputAmount), nunca os dois.
 */
public enum TransferAmountType {
    SOURCE,
    DESTINATION
}
