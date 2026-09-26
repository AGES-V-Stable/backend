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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
        beneficiaryService = new BeneficiaryService(beneficiaryRepository, companyRepository, new BeneficiaryValidator());
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
        assertThat(response.getLegalName()).isEqualTo("Fornecedor Teste Ltda");

        ArgumentCaptor<BeneficiaryEntity> captor = ArgumentCaptor.forClass(BeneficiaryEntity.class);
        verify(beneficiaryRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getBankName()).isEqualTo("Banco Teste");
        assertThat(captor.getValue().getCompanyId()).isEqualTo(companyId);
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
}
