package ages.vstable.backend.service;

import ages.vstable.backend.dto.beneficiary.BeneficiaryCreateRequest;
import ages.vstable.backend.dto.beneficiary.BeneficiaryResponse;
import ages.vstable.backend.entity.BeneficiaryEntity;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.enums.ComplianceStatus;
import ages.vstable.backend.exception.ForbiddenException;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.repository.BeneficiaryRepository;
import ages.vstable.backend.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import ages.vstable.backend.repository.specification.BeneficiarySpecification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BeneficiaryService {

    private final BeneficiaryRepository beneficiaryRepository;
    private final CompanyRepository companyRepository;
    private final BeneficiaryValidator beneficiaryValidator;

    public BeneficiaryResponse create(UUID companyId, BeneficiaryCreateRequest request) {
        CompanyEntity company = companyRepository.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Company not found"));

        if (company.getKybStatus() != ComplianceStatus.APPROVED) {
            throw new ForbiddenException("Empresa não verificada");
        }

        beneficiaryValidator.validate(request);

        BeneficiaryEntity beneficiary = buildBeneficiary(companyId, request);

        return toResponse(beneficiaryRepository.saveAndFlush(beneficiary));
    }

    private BeneficiaryEntity buildBeneficiary(UUID companyId, BeneficiaryCreateRequest request) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        return BeneficiaryEntity.builder()
                .companyId(companyId)
                .beneficiaryType(request.getBeneficiaryType())
                .accountHolderName(request.getLegalName())
                .identificationDocument(request.getIdentificationDocument())
                .country(request.getCountry())
                .nickname(request.getNickname())
                .internalDescription(request.getInternalDescription())
                .receivingMethod(request.getReceivingMethod())
                .bankName(request.getBankName())
                .swiftBic(request.getSwiftBic())
                .bankCode(request.getBankCode())
                .branchNumber(request.getBranchNumber())
                .accountNumber(request.getAccountNumber())
                .currency(request.getCurrency())
                .walletAddress(request.getWalletAddress())
                .blockchainNetwork(request.getBlockchainNetwork())
                .walletMemo(request.getWalletMemo())
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    @Transactional(readOnly = true)
    public Page<BeneficiaryResponse> findBeneficiaries(UUID companyId, String search, String document, String country,
            Pageable pageable) {
        return beneficiaryRepository.findAll(
                BeneficiarySpecification.filterBy(companyId, search, document, country),
                pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public BeneficiaryResponse findById(UUID id) {
        return beneficiaryRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new NotFoundException("Beneficiário não encontrado"));
    }

    private BeneficiaryResponse toResponse(BeneficiaryEntity entity) {
        BeneficiaryResponse response = new BeneficiaryResponse();

        response.setId(entity.getId());
        response.setCompanyId(entity.getCompanyId());
        response.setNickname(entity.getNickname());
        response.setInternalDescription(entity.getInternalDescription());
        response.setReceivingMethod(entity.getReceivingMethod());
        response.setIdentificationDocument(entity.getIdentificationDocument());
        response.setBankCode(entity.getBankCode());
        response.setBranchNumber(entity.getBranchNumber());
        response.setAccountNumber(entity.getAccountNumber());
        response.setCountry(entity.getCountry());
        response.setBlockchainNetwork(entity.getBlockchainNetwork());
        response.setWalletAddress(entity.getWalletAddress());
        response.setWalletMemo(entity.getWalletMemo());
        response.setCreatedAt(entity.getCreatedAt());

        response.setBeneficiaryType(entity.getBeneficiaryType());
        response.setLegalName(entity.getAccountHolderName());
        response.setSwiftBic(entity.getSwiftBic());
        response.setCurrency(entity.getCurrency());

        response.setPixKey(entity.getPixKey()); // * */
        response.setAccountHolderName(entity.getAccountHolderName()); // * */
        response.setUpdatedAt(entity.getUpdatedAt()); // * */

        return response;
    }
}
