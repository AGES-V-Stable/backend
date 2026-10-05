package ages.vstable.backend.dto.transfer;

import ages.vstable.backend.entity.enums.TransactionStatus;
import ages.vstable.backend.entity.enums.TransferDirection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Filtros do histórico de transferências. Todos opcionais.
 *
 * @param search      nome (razão social / fantasia) ou CNPJ da empresa
 * @param beneficiary nome do beneficiário (pagamentos) ou do pagador externo (recebimentos)
 * @param startDate   data de criação inicial (inclusive), no fuso de São Paulo
 * @param endDate     data de criação final (inclusive), no fuso de São Paulo
 * @param minAmount   valor mínimo na moeda estrangeira
 * @param maxAmount   valor máximo na moeda estrangeira
 */
public record TransferFilter(
        UUID companyId,
        String search,
        String beneficiary,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        TransactionStatus status,
        TransferDirection direction) {
}
