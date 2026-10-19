package ages.vstable.backend.entity.enums;

import java.util.Locale;

/**
 * Etapa do onboarding de uma empresa na BlindPay, derivada de {@code kyc_status},
 * {@code aml_status} e do aceite dos termos. Só {@link #APPROVED} habilita operações
 * na BlindPay: cliente criado, aceite iniciado ou KYB aprovado sem AML liberado não bastam.
 */
public enum BlindPayOnboardingStatus {
    /** Nenhuma etapa iniciada; não é persistido. */
    NOT_STARTED,
    /** Conta local criada; termos ainda não aceitos. */
    TOS_PENDING,
    /** Termos aceitos; cadastro KYB ainda não enviado. */
    TOS_ACCEPTED,
    /** O envio do KYB falhou e nenhum cliente foi criado; pode ser reenviado. */
    SUBMISSION_FAILED,
    VERIFYING,
    PENDING_REVIEW,
    AWAITING_CONTRACT,
    /** A BlindPay pediu informações adicionais (RFI). */
    RFI_REQUESTED,
    /** KYB aprovado, mas a triagem AML ainda não terminou ou falhou tecnicamente. */
    AML_PENDING,
    /** A triagem AML encontrou ocorrência; depende de revisão da BlindPay. */
    AML_HIT,
    APPROVED,
    /** Rejeitado. Atualizar o cliente não reinicia a verificação. */
    REJECTED,
    DEPRECATED;

    public static BlindPayOnboardingStatus fromProvider(String kycStatus, String amlStatus, boolean tosAccepted) {
        String kyc = normalize(kycStatus);
        return switch (kyc) {
            case "rejected" -> REJECTED;
            case "deprecated" -> DEPRECATED;
            case "compliance_request" -> RFI_REQUESTED;
            case "pending_review" -> PENDING_REVIEW;
            case "awaiting_contract" -> AWAITING_CONTRACT;
            case "approved", "approved_rfi" -> approvedOutcome(amlStatus, tosAccepted);
            // "verifying" e qualquer valor novo da BlindPay: em análise, nunca aprovado.
            default -> VERIFYING;
        };
    }

    /** Converte o valor persistido; valores legados ("CREATED", "PENDING"...) viram {@code null}. */
    public static BlindPayOnboardingStatus parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public boolean isTerminalRejection() {
        return this == REJECTED || this == DEPRECATED;
    }

    private static BlindPayOnboardingStatus approvedOutcome(String amlStatus, boolean tosAccepted) {
        String aml = normalize(amlStatus);
        if ("hit".equals(aml)) {
            return AML_HIT;
        }
        if (!"clear".equals(aml)) {
            return AML_PENDING;
        }
        return tosAccepted ? APPROVED : TOS_PENDING;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
