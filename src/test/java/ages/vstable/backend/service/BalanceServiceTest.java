package ages.vstable.backend.service;

import ages.vstable.backend.dto.balance.BalanceResponse;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.repository.CompanyRepository;
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

@ExtendWith(MockitoExtension.class)
class BalanceServiceTest {

    @Mock
    private CompanyRepository companyRepository;

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
}
