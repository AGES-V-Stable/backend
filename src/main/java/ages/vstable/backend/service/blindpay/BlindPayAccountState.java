package ages.vstable.backend.service.blindpay;

import ages.vstable.backend.entity.ProviderAccountEntity;
import ages.vstable.backend.entity.enums.BlindPayOnboardingStatus;

import java.time.OffsetDateTime;

/**
 * Regras compartilhadas pela sincronização direta e pelos webhooks: conferir que os
 * dados recebidos são do cliente da empresa e aplicar os status de compliance.
 */
final class BlindPayAccountState {

    private BlindPayAccountState() {
    }

    /**
     * O cliente recebido é o mesmo da conta: mesmo {@code customer_id} e, quando
     * presentes, mesmo {@code external_id} (id da empresa) e mesma instância.
     */
    static boolean belongsTo(
            ProviderAccountEntity account,
            String customerId,
            String externalId,
            String instanceId,
            String configuredInstanceId
    ) {
        return customerId != null
                && customerId.equals(account.getExternalCustomerId())
                && (externalId == null || externalId.equals(account.getCompany().getId().toString()))
                && (instanceId == null || instanceId.equals(configuredInstanceId));
    }

    /**
     * Aplica os status do provedor. Informações sem {@code kyc_status} ou mais antigas
     * que a última aplicada (webhook fora de ordem) são descartadas.
     *
     * @return {@code true} se a conta foi alterada
     */
    static boolean apply(
            ProviderAccountEntity account,
            String kycStatus,
            String amlStatus,
            Boolean tosAccepted,
            String tosId,
            OffsetDateTime providerUpdatedAt,
            OffsetDateTime now
    ) {
        if (kycStatus == null || kycStatus.isBlank()) {
            return false;
        }
        if (providerUpdatedAt != null && account.getProviderUpdatedAt() != null
                && providerUpdatedAt.isBefore(account.getProviderUpdatedAt())) {
            return false;
        }

        account.setKycStatus(kycStatus);
        account.setAmlStatus(amlStatus);
        if (account.getTosId() == null && tosId != null && !tosId.isBlank()) {
            account.setTosId(tosId);
        }
        if (Boolean.TRUE.equals(tosAccepted) && account.getTosAcceptedAt() == null) {
            account.setTosAcceptedAt(now);
        }
        if (providerUpdatedAt != null) {
            account.setProviderUpdatedAt(providerUpdatedAt);
        }
        account.setStatus(BlindPayOnboardingStatus.fromProvider(
                kycStatus, amlStatus, account.getTosAcceptedAt() != null).name());
        account.setSynchronizedAt(now);
        account.setUpdatedAt(now);
        return true;
    }
}
