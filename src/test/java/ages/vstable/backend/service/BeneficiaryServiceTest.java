package ages.vstable.backend.service;

import ages.vstable.backend.dto.beneficiary.BeneficiaryCreateRequest;
import ages.vstable.backend.dto.beneficiary.BeneficiaryResponse;
import ages.vstable.backend.entity.BeneficiaryEntity;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.enums.BlockchainNetwork;
import ages.vstable.backend.entity.enums.ComplianceStatus;
import ages.vstable.backend.entity.enums.ReceivingMethod;
import ages.vstable.backend.exception.ForbiddenException;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.repository.BeneficiaryRepository;
import ages.vstable.backend.repository.CompanyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BeneficiaryServiceTest {

    @Mock
    private BeneficiaryRepository beneficiaryRepository;

    @Mock
    private CompanyRepository companyRepository;

    private BeneficiaryService beneficiaryService;

    private final UUID companyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        beneficiaryService = new BeneficiaryService(beneficiaryRepository, companyRepository,
                new BeneficiaryValidator());
    }

    @Test
    void create_validBankAccountRequest_savesAndReturnsBeneficiary() {
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(approvedCompany()));
        when(beneficiaryRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            BeneficiaryEntity entity = invocation.getArgument(0);
            entity.setId(UUID.randomUUID());
            return entity;
        });

        BeneficiaryResponse response = beneficiaryService.create(companyId, bankAccountRequest());

        assertThat(response.getId()).isNotNull();
        assertThat(response.getCompanyId()).isEqualTo(companyId);
        assertThat(response.getReceivingMethod()).isEqualTo(ReceivingMethod.BANK_ACCOUNT);
        assertThat(response.getNickname()).isEqualTo("Fornecedor Principal"); // Ajuste baseado no nickname

        ArgumentCaptor<BeneficiaryEntity> captor = ArgumentCaptor.forClass(BeneficiaryEntity.class);
        verify(beneficiaryRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getCompany().getId()).isEqualTo(companyId);
    }

    @Test
    void findById_mapsAllFieldsToResponse() {
        UUID id = UUID.randomUUID();


        CompanyEntity company = new CompanyEntity();
        company.setId(companyId);
        company.setLegalName("Company Legal Name");
        company.setTradeName("Company Trade Name");
        company.setCnpj("12.345.678/0001-90");
        company.setCountry("BR");
        company.setZipCode("90000-000");
        company.setCity("Porto Alegre");
        company.setState("RS");


        BeneficiaryEntity entity = new BeneficiaryEntity();
        entity.setId(id);
        entity.setCompany(company);
        entity.setNickname("Nick");
        entity.setInternalDescription("Desc");
        entity.setReceivingMethod(ReceivingMethod.BANK_ACCOUNT);
        entity.setIdentificationDocument("12345");
        entity.setBankCode("341");
        entity.setBranchNumber("0001");
        entity.setAccountNumber("123-4");
        entity.setCountry("BR");
        entity.setAddress("Rua A");
        entity.setBlockchainNetwork(BlockchainNetwork.ethereum);
        entity.setWalletAddress("0x00");
        entity.setWalletMemo("memo");
        entity.setBeneficiaryType("LEGAL_ENTITY");
        entity.setAccountHolderName("Holder");
        entity.setSwiftBic("SWIFT");
        entity.setCurrency("BRL");
        entity.setPixKey("pix@key");
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setUpdatedAt(OffsetDateTime.now());

        when(beneficiaryRepository.findById(id)).thenReturn(Optional.of(entity));

        BeneficiaryResponse response = beneficiaryService.findById(id);

        assertThat(response.getNickname()).isEqualTo("Nick");
        assertThat(response.getInternalDescription()).isEqualTo("Desc");
        assertThat(response.getBankCode()).isEqualTo("341");
        assertThat(response.getBranchNumber()).isEqualTo("0001");
        assertThat(response.getAccountNumber()).isEqualTo("123-4");
        assertThat(response.getCountry()).isEqualTo("BR");
        assertThat(response.getAddress()).isEqualTo("Rua A");
        assertThat(response.getBlockchainNetwork()).isEqualTo(BlockchainNetwork.ethereum);
        assertThat(response.getWalletAddress()).isEqualTo("0x00");
        assertThat(response.getWalletMemo()).isEqualTo("memo");
        assertThat(response.getBeneficiaryType()).isEqualTo("LEGAL_ENTITY");
        assertThat(response.getLegalName()).isEqualTo("Holder");
        assertThat(response.getAccountHolderName()).isEqualTo("Holder");
        assertThat(response.getSwiftBic()).isEqualTo("SWIFT");
        assertThat(response.getCurrency()).isEqualTo("BRL");
        assertThat(response.getPixKey()).isEqualTo("pix@key");
        assertThat(response.getCreatedAt()).isNotNull();
        assertThat(response.getUpdatedAt()).isNotNull();
    }

    @Test
    void create_validWalletRequest_savesAndReturnsBeneficiary() {
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(approvedCompany()));
        when(beneficiaryRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            BeneficiaryEntity entity = invocation.getArgument(0);
            entity.setId(UUID.randomUUID());
            return entity;
        });

        BeneficiaryResponse response = beneficiaryService.create(companyId, walletRequest());

        assertThat(response.getReceivingMethod()).isEqualTo(ReceivingMethod.CRYPTO_WALLET);
        assertThat(response.getBlockchainNetwork()).isEqualTo(BlockchainNetwork.ethereum);
        assertThat(response.getWalletAddress()).isEqualTo("0xABCDEF1234567890");
    }

    @Test
    void create_companyNotFound_throwsNotFound() {
        when(companyRepository.findById(companyId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> beneficiaryService.create(companyId, bankAccountRequest()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void create_companyNotApproved_throwsForbidden() {
        CompanyEntity company = approvedCompany();
        company.setKybStatus(ComplianceStatus.PENDING);
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));

        assertThatThrownBy(() -> beneficiaryService.create(companyId, bankAccountRequest()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void create_missingBankFields_throwsIllegalArgument() {
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(approvedCompany()));

        BeneficiaryCreateRequest request = bankAccountRequest();
        request.setBankName(null);

        assertThatThrownBy(() -> beneficiaryService.create(companyId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("bankName: Campo obrigatório.");
    }

    @Test
    void create_missingWalletFields_throwsIllegalArgument() {
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(approvedCompany()));

        BeneficiaryCreateRequest request = walletRequest();
        request.setWalletAddress(null);

        assertThatThrownBy(() -> beneficiaryService.create(companyId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("walletAddress: Campo obrigatório.");
    }

    @Test
    void findBeneficiaries_returnsMappedPage() {
        UUID companyIdForSearch = UUID.randomUUID();
        BeneficiaryEntity entity = buildBeneficiary(UUID.randomUUID(), companyIdForSearch);
        Page<BeneficiaryEntity> entityPage = new PageImpl<>(List.of(entity));
        Pageable pageable = PageRequest.of(0, 10);

        when(beneficiaryRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(entityPage);

        Page<BeneficiaryResponse> result = beneficiaryService.findBeneficiaries(
                companyIdForSearch, "search_term", "123", "Brasil", pageable);

        assertThat(result.getContent()).isNotEmpty();
    }

    @Test
    void findById_returnsBeneficiary_whenExists() {
        UUID id = UUID.randomUUID();
        UUID companyIdForSearch = UUID.randomUUID();
        BeneficiaryEntity entity = buildBeneficiary(id, companyIdForSearch);

        when(beneficiaryRepository.findById(id)).thenReturn(Optional.of(entity));

        BeneficiaryResponse result = beneficiaryService.findById(id);

        assertThat(result).isNotNull();
    }

    @Test
    void findById_throwsNotFoundException_whenBeneficiaryDoesNotExist() {
        UUID id = UUID.randomUUID();
        when(beneficiaryRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> beneficiaryService.findById(id))
                .isInstanceOf(NotFoundException.class);
    }

    private CompanyEntity approvedCompany() {
        CompanyEntity company = new CompanyEntity();
        company.setId(companyId);
        company.setKybStatus(ComplianceStatus.APPROVED);
        return company;
    }

    private BeneficiaryCreateRequest bankAccountRequest() {
        BeneficiaryCreateRequest request = new BeneficiaryCreateRequest();
        request.setBeneficiaryType("LEGAL_ENTITY");
        request.setLegalName("Fornecedor Teste Ltda");
        request.setIdentificationDocument("11222333000181");
        request.setCountry("Brasil");
        request.setAddress("Rua Teste, 123");
        request.setReceivingMethod(ReceivingMethod.BANK_ACCOUNT);
        request.setNickname("Fornecedor Principal");
        request.setBankName("Banco Teste");
        request.setSwiftBic("TESTBRSPXXX");
        request.setAccountNumber("12345-6");
        request.setCurrency("BRL");
        request.setConfirmed(true);
        return request;
    }

    private BeneficiaryCreateRequest walletRequest() {
        BeneficiaryCreateRequest request = new BeneficiaryCreateRequest();
        request.setBeneficiaryType("LEGAL_ENTITY");
        request.setLegalName("Fornecedor Teste Ltda");
        request.setIdentificationDocument("11222333000181");
        request.setCountry("Brasil");
        request.setAddress("Rua Teste, 123");
        request.setReceivingMethod(ReceivingMethod.CRYPTO_WALLET);
        request.setNickname("Carteira Principal");
        request.setWalletAddress("0xABCDEF1234567890");
        request.setBlockchainNetwork(BlockchainNetwork.ethereum);
        request.setConfirmed(true);
        return request;
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

    @Test
    void findBeneficiaries_executesSpecificationWithoutErrors() {
        UUID companyIdForSearch = UUID.randomUUID();
        String search = "Teste";
        String document = "123456";
        String country = "Brasil";
        Pageable pageable = PageRequest.of(0, 10);

        BeneficiaryEntity entity = buildBeneficiary(UUID.randomUUID(), companyIdForSearch);
        Page<BeneficiaryEntity> entityPage = new PageImpl<>(List.of(entity));

        ArgumentCaptor<Specification<BeneficiaryEntity>> specCaptor = ArgumentCaptor.forClass(Specification.class);

        when(beneficiaryRepository.findAll(specCaptor.capture(), eq(pageable))).thenReturn(entityPage);

        Page<BeneficiaryResponse> result = beneficiaryService.findBeneficiaries(
                companyIdForSearch, search, document, country, pageable);

        assertThat(result.getContent()).isNotEmpty();

        Specification<BeneficiaryEntity> capturedSpec = specCaptor.getValue();
        assertThat(capturedSpec).isNotNull();

        jakarta.persistence.criteria.Root root = org.mockito.Mockito.mock(jakarta.persistence.criteria.Root.class);
        jakarta.persistence.criteria.CriteriaQuery query = org.mockito.Mockito
                .mock(jakarta.persistence.criteria.CriteriaQuery.class);
        jakarta.persistence.criteria.CriteriaBuilder cb = org.mockito.Mockito
                .mock(jakarta.persistence.criteria.CriteriaBuilder.class);

        jakarta.persistence.criteria.Path companyPath = org.mockito.Mockito
                .mock(jakarta.persistence.criteria.Path.class);
        org.mockito.Mockito.when(root.get("company")).thenReturn(companyPath);

        jakarta.persistence.criteria.Path genericPath = org.mockito.Mockito
                .mock(jakarta.persistence.criteria.Path.class);
        org.mockito.Mockito.when(companyPath.get("id")).thenReturn(genericPath);
        org.mockito.Mockito.when(root.get("nickname")).thenReturn(genericPath);
        org.mockito.Mockito.when(root.get("identificationDocument")).thenReturn(genericPath);
        org.mockito.Mockito.when(root.get("country")).thenReturn(genericPath);

        org.mockito.Mockito.when(cb.lower(org.mockito.ArgumentMatchers.any())).thenReturn(genericPath);

        capturedSpec.toPredicate(root, query, cb);
    }

    @Test
    void findBeneficiaries_executesSpecificationWithNullFilters() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<BeneficiaryEntity> entityPage = new PageImpl<>(List.of());

        ArgumentCaptor<Specification<BeneficiaryEntity>> specCaptor = ArgumentCaptor.forClass(Specification.class);

        when(beneficiaryRepository.findAll(specCaptor.capture(), eq(pageable))).thenReturn(entityPage);

        beneficiaryService.findBeneficiaries(null, null, null, null, pageable);

        Specification<BeneficiaryEntity> capturedSpec = specCaptor.getValue();

        jakarta.persistence.criteria.Root root = org.mockito.Mockito.mock(jakarta.persistence.criteria.Root.class);
        jakarta.persistence.criteria.CriteriaQuery query = org.mockito.Mockito
                .mock(jakarta.persistence.criteria.CriteriaQuery.class);
        jakarta.persistence.criteria.CriteriaBuilder cb = org.mockito.Mockito
                .mock(jakarta.persistence.criteria.CriteriaBuilder.class);

        capturedSpec.toPredicate(root, query, cb);

        assertThat(capturedSpec).isNotNull();
    }
}