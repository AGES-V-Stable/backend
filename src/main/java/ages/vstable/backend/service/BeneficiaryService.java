package ages.vstable.backend.service;

import ages.vstable.backend.dto.beneficiary.BeneficiaryResponse;
import ages.vstable.backend.entity.BeneficiaryEntity;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.repository.BeneficiaryRepository;
import ages.vstable.backend.repository.specification.BeneficiarySpecification;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BeneficiaryService {

    private final BeneficiaryRepository beneficiaryRepository;

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
        response.setCompanyId(entity.getCompany().getId());
        response.setNickname(entity.getNickname());
        response.setInternalDescription(entity.getInternalDescription());
        response.setReceivingMethod(entity.getReceivingMethod());
        response.setPixKey(entity.getPixKey());
        response.setIdentificationDocument(entity.getIdentificationDocument());
        response.setAccountHolderName(entity.getAccountHolderName());
        response.setBankCode(entity.getBankCode());
        response.setBranchNumber(entity.getBranchNumber());
        response.setAccountNumber(entity.getAccountNumber());
        response.setAccountType(entity.getAccountType());
        response.setCountry(entity.getCountry());
        response.setBlockchainNetwork(entity.getBlockchainNetwork());
        response.setWalletAddress(entity.getWalletAddress());
        response.setWalletMemo(entity.getWalletMemo());
        response.setCreatedAt(entity.getCreatedAt());
        response.setUpdatedAt(entity.getUpdatedAt());
        return response;
    }
}