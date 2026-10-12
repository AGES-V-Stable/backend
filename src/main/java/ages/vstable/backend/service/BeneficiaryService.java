package ages.vstable.backend.service;

import ages.vstable.backend.dto.beneficiary.BeneficiaryCreateRequest;
import ages.vstable.backend.dto.beneficiary.BeneficiaryResponse;
import ages.vstable.backend.entity.BeneficiaryEntity;
import ages.vstable.backend.entity.AveniaKycVerificationEntity;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.enums.ComplianceStatus;
import ages.vstable.backend.entity.enums.BankAccountType;
import ages.vstable.backend.exception.ForbiddenException;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.external.avenia.AveniaBeneficiaryService;
import ages.vstable.backend.external.avenia.dto.AveniaBeneficiaryRegistration;
import ages.vstable.backend.repository.AveniaKycVerificationRepository;
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
    private final AveniaBeneficiaryService aveniaBeneficiaryService;
    private final AveniaKycVerificationRepository aveniaKycVerificationRepository;
    private final AveniaSubAccountProvisioningService subAccountProvisioningService;

    @Transactional
    public BeneficiaryResponse create(UUID companyId, UUID currentUserId, BeneficiaryCreateRequest request) {
        CompanyEntity company = companyRepository.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Company not found"));

        if (company.getKybStatus() != ComplianceStatus.APPROVED) {
            throw new ForbiddenException("Empresa não verificada");
        }

        beneficiaryValidator.validate(request);

        BeneficiaryEntity beneficiary = buildBeneficiary(company, request);
        String subAccountId = resolveSubAccountId(currentUserId);
        AveniaBeneficiaryRegistration registration = aveniaBeneficiaryService.register(beneficiary, subAccountId);
        beneficiary.setAveniaId(registration.bankAccountId());
        beneficiary.setAveniaWalletId(registration.walletId());
        beneficiary.setAveniaSubAccountId(subAccountId);

        return toResponse(beneficiaryRepository.saveAndFlush(beneficiary));
    }

    private String resolveSubAccountId(UUID currentUserId) {
        if (currentUserId == null) {
            throw new UnprocessableEntityException("Usuário autenticado não identificado");
        }
        AveniaKycVerificationEntity kyc = aveniaKycVerificationRepository
                .findFirstByUserIdOrderByCreatedAtDesc(currentUserId)
                .orElseThrow(() -> new UnprocessableEntityException(
                        "Usuário não possui verificação KYC associada; conclua o KYC antes de cadastrar beneficiários"));
        return subAccountProvisioningService.ensureSubAccountId(kyc);
    }

    private BeneficiaryEntity buildBeneficiary(CompanyEntity company, BeneficiaryCreateRequest request) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        return BeneficiaryEntity.builder()
                .company(company)
                .beneficiaryType(request.getBeneficiaryType())
                .accountHolderName(request.getLegalName())
                .identificationDocument(request.getIdentificationDocument())
                .country(request.getCountry())
                .address(request.getAddress())
                .nickname(request.getNickname())
                .internalDescription(request.getInternalDescription())
                .receivingMethod(request.getReceivingMethod())
                .pixKey(request.getPixKey())
                .bankName(request.getBankName())
                .swiftBic(request.getSwiftBic())
                .bankCode(request.getBankCode())
                .branchNumber(request.getBranchNumber())
                .accountNumber(request.getAccountNumber())
                .currency(request.getCurrency())
                .paymentRail(defaultText(request.getPaymentRail(), "international_swift"))
                .accountClass(defaultText(request.getAccountClass(),
                        "INDIVIDUAL".equalsIgnoreCase(request.getBeneficiaryType()) ? "individual" : "business"))
                .accountType(parseAccountType(request.getAccountType()))
                .recipientRelationship(defaultText(request.getRecipientRelationship(), "vendor_or_supplier"))
                .iban(request.getIban())
                .routingNumber(request.getRoutingNumber())
                .addressLine1(defaultText(request.getAddressLine1(), request.getAddress()))
                .addressLine2(request.getAddressLine2())
                .city(request.getCity())
                .stateProvinceRegion(request.getStateProvinceRegion())
                .postalCode(request.getPostalCode())
                .countryCode(request.getCountryCode())
                .bankAddressLine1(request.getBankAddressLine1())
                .bankAddressLine2(request.getBankAddressLine2())
                .bankCity(request.getBankCity())
                .bankStateProvinceRegion(request.getBankStateProvinceRegion())
                .bankPostalCode(request.getBankPostalCode())
                .bankCountryCode(request.getBankCountryCode())
                .swiftPaymentCode(request.getSwiftPaymentCode())
                .walletAddress(request.getWalletAddress())
                .blockchainNetwork(request.getBlockchainNetwork())
                .walletMemo(request.getWalletMemo())
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private BankAccountType parseAccountType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if ("saving".equalsIgnoreCase(value)) {
            return BankAccountType.savings;
        }
        return BankAccountType.valueOf(value.toLowerCase());
    }

    @Transactional(readOnly = true)
    public Page<BeneficiaryResponse> findBeneficiaries(UUID companyId, String search, String document, String country,
            Pageable pageable) {
        return beneficiaryRepository.findAll(
                BeneficiarySpecification.filterBy(companyId, search, document, country),
                pageable).map(this::toResponse);
    }

    /** Detail scoped to one company: a beneficiary of another company is reported as not found. */
    @Transactional(readOnly = true)
    public BeneficiaryResponse findByIdForCompany(UUID companyId, UUID id) {
        return beneficiaryRepository.findById(id)
                .filter(beneficiary -> beneficiary.getCompany().getId().equals(companyId))
                .map(this::toResponse)
                .orElseThrow(() -> new NotFoundException("Beneficiário não encontrado"));
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
        response.setBankName(entity.getBankName());
        response.setAccountType(entity.getAccountType());
        response.setIdentificationDocument(entity.getIdentificationDocument());
        response.setBankCode(entity.getBankCode());
        response.setBranchNumber(entity.getBranchNumber());
        response.setAccountNumber(entity.getAccountNumber());
        response.setCountry(entity.getCountry());
        response.setAddress(entity.getAddress());
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
