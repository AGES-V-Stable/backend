package ages.vstable.backend.service;

import ages.vstable.backend.dto.transaction.TransferDetailsResponse;

/**
 * Origem do documento do comprovante. Hoje o PDF é gerado a partir dos dados registrados
 * ({@link TransferReceiptPdfGenerator}); se o comprovante passar a vir da Avenia, basta outra
 * implementação, sem mudar {@link TransferDetailsService}.
 */
public interface TransferReceiptProvider {

    /** Bytes do PDF do comprovante da transferência descrita. */
    byte[] generate(TransferDetailsResponse details);
}
