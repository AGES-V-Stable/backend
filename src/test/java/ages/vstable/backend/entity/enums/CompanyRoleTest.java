package ages.vstable.backend.entity.enums;

import org.junit.jupiter.api.Test;

import static ages.vstable.backend.entity.enums.CompanyPermission.BALANCE_READ;
import static ages.vstable.backend.entity.enums.CompanyPermission.BENEFICIARY_READ;
import static ages.vstable.backend.entity.enums.CompanyPermission.BENEFICIARY_WRITE;
import static ages.vstable.backend.entity.enums.CompanyPermission.COMPANY_READ;
import static ages.vstable.backend.entity.enums.CompanyPermission.MEMBERS_MANAGE;
import static ages.vstable.backend.entity.enums.CompanyPermission.MEMBERS_READ;
import static ages.vstable.backend.entity.enums.CompanyPermission.SELF_MEMBERSHIP_READ;
import static ages.vstable.backend.entity.enums.CompanyPermission.TRANSFER_CREATE;
import static ages.vstable.backend.entity.enums.CompanyPermission.TRANSFER_READ;
import static org.assertj.core.api.Assertions.assertThat;

class CompanyRoleTest {

    @Test
    void permissionsFor_activeOwner_receivesEveryPermissionInContractOrder() {
        // Act
        var permissions = CompanyRole.OWNER.permissionsFor(MembershipStatus.ACTIVE);

        // Assert
        assertThat(permissions).containsExactly(
                COMPANY_READ, BALANCE_READ, BENEFICIARY_READ, TRANSFER_READ, SELF_MEMBERSHIP_READ,
                BENEFICIARY_WRITE, TRANSFER_CREATE, MEMBERS_READ, MEMBERS_MANAGE);
    }

    @Test
    void permissionsFor_activeOperator_operatesButDoesNotManageMembers() {
        // Act
        var permissions = CompanyRole.OPERATOR.permissionsFor(MembershipStatus.ACTIVE);

        // Assert
        assertThat(permissions).containsExactly(
                COMPANY_READ, BALANCE_READ, BENEFICIARY_READ, TRANSFER_READ, SELF_MEMBERSHIP_READ,
                BENEFICIARY_WRITE, TRANSFER_CREATE);
        assertThat(permissions).doesNotContain(MEMBERS_READ, MEMBERS_MANAGE);
    }

    @Test
    void permissionsFor_activeViewer_onlyReads() {
        // Act
        var permissions = CompanyRole.VIEWER.permissionsFor(MembershipStatus.ACTIVE);

        // Assert
        assertThat(permissions).containsExactly(
                COMPANY_READ, BALANCE_READ, BENEFICIARY_READ, TRANSFER_READ, SELF_MEMBERSHIP_READ);
    }

    @Test
    void permissionsFor_removedMembership_onlyAllowsReadingOwnAccess() {
        for (CompanyRole role : CompanyRole.values()) {
            // Act
            var permissions = role.permissionsFor(MembershipStatus.REMOVED);

            // Assert
            assertThat(permissions).as("perfil %s removido", role).containsExactly(SELF_MEMBERSHIP_READ);
        }
    }
}
