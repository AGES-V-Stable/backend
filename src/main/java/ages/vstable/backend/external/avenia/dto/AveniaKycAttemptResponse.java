package ages.vstable.backend.external.avenia.dto;

import lombok.Data;

/**
 * GET /v2/kyc/attempts/{id} envelopa a tentativa em "attempt" (diferente da
 * listagem, GET /v2/kyc/attempts/?..., que devolve "attempts" já achatado).
 * Shape confirmado contra o sandbox real em 2026-09-23.
 */
@Data
public class AveniaKycAttemptResponse {
    private AveniaKycAttempt attempt;
}
