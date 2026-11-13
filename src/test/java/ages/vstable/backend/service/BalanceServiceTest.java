package ages.vstable.backend.service;

import ages.vstable.backend.dto.balance.BalanceResponse;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.AveniaKycVerificationEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.ComplianceStatus;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.support.TestCompanyUsers;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.exception.ForbiddenException;
import ages.vstable.backend.repository.CompanyRepository;
import ages.vstable.backend.repository.AveniaKycVerificationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class BalanceServiceTest {

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private AveniaKycVerificationRepository kycRepository;

    @InjectMocks
    private BalanceService service;

    private final UUID companyId = UUID.randomUUID();

    @ParameterizedTest
    @CsvSource({
            "0, 0.00",
            "10.5, 10.50",
            "485200, 485200.00",
            "1234567890123.45, 1234567890123.45"
    })
    void getBalance_returnsDecimalStringWithTwoPlaces(String stored, String expected) {
        CompanyEntity company = CompanyEntity.builder().availableBalanceBrl(new BigDecimal(stored)).build();
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));

        BalanceResponse response = service.getBalance(companyId);

        assertThat(response.availableBalanceBrl()).isEqualTo(expected);
    }

    @Test
    void getBalance_treatsMissingBalanceAsZero() {
        CompanyEntity company = CompanyEntity.builder().availableBalanceBrl(null).build();
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));

        BalanceResponse response = service.getBalance(companyId);

        assertThat(response.availableBalanceBrl()).isEqualTo("0.00");
    }

    @Test
    void getBalance_throwsNotFoundWhenCompanyDoesNotExist() {
        when(companyRepository.findById(companyId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getBalance(companyId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void addBalance_updatesBalanceForVerifiedUser() {
        UserEntity user = TestCompanyUsers.member(companyId, CompanyRole.OWNER);
        UUID userId = user.getId();
        CompanyEntity company = CompanyEntity.builder()
                .id(companyId)
                .availableBalanceBrl(new BigDecimal("100.00"))
                .build();
        AveniaKycVerificationEntity verification = AveniaKycVerificationEntity.builder()
                .status(ComplianceStatus.APPROVED)
                .build();

        when(kycRepository.findFirstByUserIdOrderByCreatedAtDesc(userId))
                .thenReturn(Optional.of(verification));
        when(companyRepository.findByIdForUpdate(companyId))
                .thenReturn(Optional.of(company));

        BalanceResponse response = service.addBalance(user, new BigDecimal("20.50"));

        assertThat(response.availableBalanceBrl()).isEqualTo("120.50");
        assertThat(company.getAvailableBalanceBrl()).isEqualByComparingTo("120.50");
    }

    @Test
    void addBalance_rejectsUserWithPendingKyc() {
        UserEntity user = TestCompanyUsers.member(companyId, CompanyRole.OWNER);
        UUID userId = user.getId();
        AveniaKycVerificationEntity verification = AveniaKycVerificationEntity.builder()
                .status(ComplianceStatus.PENDING)
                .build();

        when(kycRepository.findFirstByUserIdOrderByCreatedAtDesc(userId))
                .thenReturn(Optional.of(verification));

        assertThatThrownBy(() -> service.addBalance(user, new BigDecimal("20.50")))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(companyRepository);
    }

    @Test
    void addBalance_rejectsRemovedOwnerBeforeLookingUpKycOrBalance() {
        UserEntity user = TestCompanyUsers.removedMember(companyId, CompanyRole.OWNER);
        assertThatThrownBy(() -> service.addBalance(user, new BigDecimal("20.50")))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(kycRepository, companyRepository);
    }

    @Test
    void addBalance_rejectsViewerBeforeLookingUpKycOrBalance() {
        UserEntity user = TestCompanyUsers.member(companyId, CompanyRole.VIEWER);
        assertThatThrownBy(() -> service.addBalance(user, new BigDecimal("20.50")))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(kycRepository, companyRepository);
    }
}
