package ages.vstable.backend.external.avenia;

import ages.vstable.backend.external.avenia.dto.AveniaReferenceRateRequest;

import java.math.BigDecimal;

/** Market reference reads cannot return executable quote tokens or create tickets. */
public interface AveniaReferenceRateGateway {
    BigDecimal getReferenceRate(AveniaReferenceRateRequest request);
}
