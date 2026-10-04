package ages.vstable.backend.exception;

import lombok.Getter;

import java.util.Set;

@Getter
public class BlindPayIntegrationException extends RuntimeException {

    /**
     * Códigos que a BlindPay responde com 4xx mas documenta como "retryable":
     * a mesma requisição pode ser repetida depois, sem alteração.
     */
    private static final Set<String> RETRYABLE_ERROR_CODES = Set.of(
            "BLOCKCHAIN_CALL_FAILED",
            "PAYOUTS_ALLOWANCE_NOT_CONFIRMED",
            "PAYINS_CUSTOMER_USD_REGISTRATION_PENDING");

    private static final String AUTHENTICATION_ERROR_PREFIX = "AUTH_";

    private final Integer providerStatus;
    private final String providerErrorCode;
    private final String traceId;
    private final boolean retryable;

    public BlindPayIntegrationException(
            String message,
            Integer providerStatus,
            String providerErrorCode,
            String traceId,
            boolean retryable,
            Throwable cause
    ) {
        super(message, cause);
        this.providerStatus = providerStatus;
        this.providerErrorCode = providerErrorCode;
        this.traceId = traceId;
        this.retryable = retryable;
    }

    public static BlindPayIntegrationException configuration(String message, Throwable cause) {
        return new BlindPayIntegrationException(message, null, null, null, false, cause);
    }

    public static BlindPayIntegrationException communication(String message, Throwable cause) {
        return new BlindPayIntegrationException(message, null, null, null, true, cause);
    }

    public static BlindPayIntegrationException invalidResponse(String message) {
        return new BlindPayIntegrationException(message, null, null, null, false, null);
    }

    public static BlindPayIntegrationException response(
            String message,
            int providerStatus,
            String providerErrorCode,
            String traceId,
            Throwable cause
    ) {
        boolean retryable = providerStatus == 429
                || providerStatus >= 500
                || (providerErrorCode != null && RETRYABLE_ERROR_CODES.contains(providerErrorCode));
        return new BlindPayIntegrationException(
                message, providerStatus, providerErrorCode, traceId, retryable, cause);
    }

    /**
     * A BlindPay entendeu a requisição mas recusou a operação por regra de negócio
     * (KYC pendente, saldo insuficiente, cotação expirada...). Falhas de autenticação
     * da nossa API key não entram aqui: são problema de configuração do backend.
     */
    public boolean isRejectedByProvider() {
        return !retryable
                && providerStatus != null
                && providerStatus >= 400
                && providerStatus < 500
                && providerStatus != 401
                && (providerErrorCode == null || !providerErrorCode.startsWith(AUTHENTICATION_ERROR_PREFIX));
    }
}
