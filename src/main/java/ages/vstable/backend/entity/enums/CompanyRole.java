package ages.vstable.backend.entity.enums;

import java.util.List;

/**
 * Perfil de acesso de um usuário em uma empresa. Não se confunde com o papel global
 * {@code ADMIN} da plataforma, que não herda vínculo empresarial.
 */
public enum CompanyRole {
    /** Proprietário/gerente: opera a empresa e gerencia membros e convites. */
    OWNER,
    /** Operador: consulta e opera beneficiários e transferências. */
    OPERATOR,
    /** Visualizador: só consulta. */
    VIEWER;

    private static final List<CompanyPermission> VIEWER_PERMISSIONS = List.of(
            CompanyPermission.COMPANY_READ,
            CompanyPermission.BALANCE_READ,
            CompanyPermission.BENEFICIARY_READ,
            CompanyPermission.TRANSFER_READ,
            CompanyPermission.SELF_MEMBERSHIP_READ);

    private static final List<CompanyPermission> OPERATOR_PERMISSIONS = List.of(
            CompanyPermission.COMPANY_READ,
            CompanyPermission.BALANCE_READ,
            CompanyPermission.BENEFICIARY_READ,
            CompanyPermission.TRANSFER_READ,
            CompanyPermission.SELF_MEMBERSHIP_READ,
            CompanyPermission.BENEFICIARY_WRITE,
            CompanyPermission.TRANSFER_CREATE);

    private static final List<CompanyPermission> OWNER_PERMISSIONS = List.of(
            CompanyPermission.COMPANY_READ,
            CompanyPermission.BALANCE_READ,
            CompanyPermission.BENEFICIARY_READ,
            CompanyPermission.TRANSFER_READ,
            CompanyPermission.SELF_MEMBERSHIP_READ,
            CompanyPermission.BENEFICIARY_WRITE,
            CompanyPermission.TRANSFER_CREATE,
            CompanyPermission.MEMBERS_READ,
            CompanyPermission.MEMBERS_MANAGE);

    /** Permissões do perfil para um vínculo na situação informada; vínculo removido só consulta o próprio acesso. */
    public List<CompanyPermission> permissionsFor(MembershipStatus status) {
        if (status == MembershipStatus.REMOVED) {
            return List.of(CompanyPermission.SELF_MEMBERSHIP_READ);
        }
        return switch (this) {
            case OWNER -> OWNER_PERMISSIONS;
            case OPERATOR -> OPERATOR_PERMISSIONS;
            case VIEWER -> VIEWER_PERMISSIONS;
        };
    }
}
