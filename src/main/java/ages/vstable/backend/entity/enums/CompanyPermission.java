package ages.vstable.backend.entity.enums;

/** Capacidades que um vínculo concede dentro da empresa. O servidor as calcula pelo perfil ({@link CompanyRole}). */
public enum CompanyPermission {
    COMPANY_READ,
    BALANCE_READ,
    BENEFICIARY_READ,
    TRANSFER_READ,
    SELF_MEMBERSHIP_READ,
    BENEFICIARY_WRITE,
    TRANSFER_CREATE,
    MEMBERS_READ,
    MEMBERS_MANAGE
}
