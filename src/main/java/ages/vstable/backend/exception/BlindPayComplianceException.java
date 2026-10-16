package ages.vstable.backend.exception;

/**
 * A empresa ainda não pode operar na BlindPay: o onboarding não terminou ou não foi
 * aprovado (KYB, AML e termos). Responde 422, como as demais regras de negócio.
 */
public class BlindPayComplianceException extends UnprocessableEntityException {
    public BlindPayComplianceException(String message) {
        super(message);
    }
}
