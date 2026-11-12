package ages.vstable.backend.dto.membership;

import ages.vstable.backend.entity.enums.CompanyRole;

/** Perfis que um convite pode atribuir: o proprietário nunca é criado por convite. */
public enum InviteRole {
    OPERATOR,
    VIEWER;

    public CompanyRole toCompanyRole() {
        return CompanyRole.valueOf(name());
    }
}
