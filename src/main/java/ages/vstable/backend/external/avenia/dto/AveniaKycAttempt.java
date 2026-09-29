package ages.vstable.backend.external.avenia.dto;

import lombok.Data;

import java.util.List;

/**
 * Tentativa de KYC Level 1 na Avenia (GET /v2/kyc/attempts/{id} ou
 * GET /v2/kyc/attempts/?levelName=level-1&amp;subAccountId=...). Shape confirmado
 * contra o sandbox real em 2026-09-23.
 */
@Data
public class AveniaKycAttempt {
    private String id;
    private String status;
    private String result;
    private String resultMessage;
    private List<String> rejectionLabels;
    private boolean retryable;
}
