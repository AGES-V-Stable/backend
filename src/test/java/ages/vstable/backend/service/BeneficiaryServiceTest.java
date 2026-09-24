package ages.vstable.backend.service;

import ages.vstable.backend.dto.beneficiary.BeneficiaryResponse;
import ages.vstable.backend.entity.BeneficiaryEntity;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.enums.ReceivingMethod;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.repository.BeneficiaryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BeneficiaryServiceTest {

    @Mock
    private BeneficiaryRepository beneficiaryRepository;

    private BeneficiaryService beneficiaryService;

    @BeforeEach
    void setUp() {
        beneficiaryService = new BeneficiaryService(beneficiaryRepository);
    }

    @Test
    void findBeneficiaries_returnsMappedPage() {
        UUID companyId = UUID.randomUUID();
        BeneficiaryEntity entity = buildBeneficiary(UUID.randomUUID(), companyId);
        Page<BeneficiaryEntity> entityPage = new PageImpl<>(List.of(entity));
        Pageable pageable = PageRequest.of(0, 10);

        when(beneficiaryRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(entityPage);

        Page<BeneficiaryResponse> result = beneficiaryService.findBeneficiaries(
                companyId, "search_term", "123", "Brasil", pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getId()).isEqualTo(entity.getId());
        assertThat(result.getContent().get(0).getCompanyId()).isEqualTo(companyId);
        assertThat(result.getContent().get(0).getNickname()).isEqualTo("Apelido Teste");
    }

    @Test
    void findById_returnsBeneficiary_whenExists() {
        UUID id = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        BeneficiaryEntity entity = buildBeneficiary(id, companyId);

        when(beneficiaryRepository.findById(id)).thenReturn(Optional.of(entity));

        BeneficiaryResponse result = beneficiaryService.findById(id);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(id);
        assertThat(result.getCompanyId()).isEqualTo(companyId);
        assertThat(result.getReceivingMethod()).isEqualTo(ReceivingMethod.BANK_ACCOUNT);
    }

    @Test
    void findById_throwsNotFoundException_whenBeneficiaryDoesNotExist() {
        UUID id = UUID.randomUUID();
        when(beneficiaryRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> beneficiaryService.findById(id))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Beneficiário não encontrado");
    }

    private BeneficiaryEntity buildBeneficiary(UUID id, UUID companyId) {
        CompanyEntity company = new CompanyEntity();
        company.setId(companyId);

        BeneficiaryEntity entity = new BeneficiaryEntity();
        entity.setId(id);
        entity.setCompany(company);
        entity.setNickname("Apelido Teste");
        entity.setReceivingMethod(ReceivingMethod.BANK_ACCOUNT);
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setUpdatedAt(OffsetDateTime.now());
        return entity;
    }
}