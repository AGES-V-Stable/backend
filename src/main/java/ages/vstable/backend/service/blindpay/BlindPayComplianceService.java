package ages.vstable.backend.service.blindpay;

import ages.vstable.backend.dto.blindpay.BlindPayComplianceStatusResponse;
import ages.vstable.backend.dto.blindpay.BlindPayDocumentType;
import ages.vstable.backend.dto.blindpay.BlindPayDocumentUploadResponse;
import ages.vstable.backend.dto.blindpay.BlindPayKybRequest;
import ages.vstable.backend.dto.blindpay.BlindPayRfiDetailsResponse;
import ages.vstable.backend.dto.blindpay.BlindPayTermsResponse;
import ages.vstable.backend.entity.BlindPayTosSessionEntity;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.ProviderAccountEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.BlindPayOnboardingStatus;
import ages.vstable.backend.entity.enums.IntegrationProvider;
import ages.vstable.backend.exception.BlindPayIntegrationException;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.external.blindpay.BlindPayApi;
import ages.vstable.backend.external.blindpay.BlindPayGateway;
import ages.vstable.backend.external.blindpay.BlindPayProperties;
import ages.vstable.backend.external.blindpay.dto.BlindPayCreateCustomerRequest;
import ages.vstable.backend.external.blindpay.dto.BlindPayCustomerCreatedResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayCustomerResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayInitiateTosRequest;
import ages.vstable.backend.external.blindpay.dto.BlindPayInitiateTosResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayRfiResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayRfiSubmitResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayUpdateCustomerRequest;
import ages.vstable.backend.external.blindpay.dto.BlindPayUploadResponse;
import ages.vstable.backend.repository.BlindPayTosSessionRepository;
import ages.vstable.backend.repository.CompanyRepository;
import ages.vstable.backend.repository.ProviderAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Onboarding KYB/AML da empresa na BlindPay: aceite hospedado dos termos, upload de
 * documentos, cadastro e atualização do cliente, sincronização de status e RFI.
 * Independente do compliance da Avenia: os status ficam na conta do provedor e não
 * alteram {@code companies.kyb_status}/{@code aml_status}.
 *
 * <p>Os dados cadastrais e documentos são repassados à BlindPay e não são persistidos;
 * o backend guarda só identificadores e status.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlindPayComplianceService {

    static final int MAX_DOCUMENT_BYTES = 3 * 1024 * 1024;
    static final int MAX_RFI_TEXT_LENGTH = 4096;
    static final int MAX_RFI_FILES = 20;
    private static final int MIN_BENEFICIAL_OWNERSHIP = 25;
    private static final String RFI_PENDING = "pending";
    private static final String HTTPS_PREFIX = "https://";
    private static final Set<BlindPayApi.Customer.OwnerRole> CONTROLLING_ROLES = Set.of(
            BlindPayApi.Customer.OwnerRole.controlling_person,
            BlindPayApi.Customer.OwnerRole.beneficial_controlling);

    private final BlindPayGateway blindPayGateway;
    private final BlindPayProperties properties;
    private final CompanyRepository companyRepository;
    private final ProviderAccountRepository providerAccountRepository;
    private final BlindPayTosSessionRepository tosSessionRepository;
    private final ObjectMapper objectMapper;

    // ---------------------------------------------------------------------
    // Termos de serviço
    // ---------------------------------------------------------------------

    /**
     * Inicia o aceite hospedado. A sessão é gravada antes da chamada para que o
     * webhook {@code tos.accept} sempre encontre a correlação com a empresa.
     */
    public BlindPayTermsResponse initiateTermsOfService(UserEntity currentUser) {
        CompanyEntity company = company(currentUser);
        ProviderAccountEntity account = findOrCreateAccount(company);
        if (account.getTosAcceptedAt() != null) {
            throw new ConflictException("BlindPay terms of service were already accepted");
        }

        OffsetDateTime now = now();
        BlindPayTosSessionEntity session = tosSessionRepository.save(BlindPayTosSessionEntity.builder()
                .company(company)
                .requestedBy(currentUser.getId())
                .idempotencyKey(UUID.randomUUID())
                .status(BlindPayTosSessionEntity.STATUS_INITIATED)
                .createdAt(now)
                .build());

        BlindPayInitiateTosResponse response = blindPayGateway.initiateTermsOfService(new BlindPayInitiateTosRequest(
                session.getIdempotencyKey(),
                account.getExternalCustomerId(),
                blankToNull(properties.getTosRedirectUrl())));
        if (response == null || response.url() == null || !response.url().startsWith(HTTPS_PREFIX)) {
            throw BlindPayIntegrationException.invalidResponse("BlindPay returned no terms of service URL");
        }
        return new BlindPayTermsResponse(response.url());
    }

    // ---------------------------------------------------------------------
    // Documentos
    // ---------------------------------------------------------------------

    /**
     * Envia um documento à BlindPay. O tipo real é detectado pelo conteúdo (não pelo
     * nome ou content-type informados) e o nome original não é repassado.
     */
    public BlindPayDocumentUploadResponse uploadDocument(
            UserEntity currentUser, BlindPayDocumentType documentType, byte[] content) {
        if (documentType == null) {
            throw new IllegalArgumentException("documentType must be provided");
        }
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("file must not be empty");
        }
        if (content.length > MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("file must have at most 3MB");
        }
        DocumentFormat format = DocumentFormat.detect(content);
        if (format == null || (documentType.imageOnly() && !format.image())) {
            throw new IllegalArgumentException(documentType.imageOnly()
                    ? "file must be a JPEG or PNG image"
                    : "file must be a PDF, JPEG or PNG");
        }

        ProviderAccountEntity account = account(company(currentUser));
        if (account == null || account.getTosAcceptedAt() == null) {
            throw new UnprocessableEntityException(
                    "Accept the BlindPay terms of service before sending documents");
        }

        BlindPayUploadResponse response = blindPayGateway.uploadFile(
                BlindPayApi.Upload.Bucket.onboarding,
                "document." + format.extension(),
                format.contentType(),
                content);
        return new BlindPayDocumentUploadResponse(response.fileUrl());
    }

    // ---------------------------------------------------------------------
    // Cadastro KYB
    // ---------------------------------------------------------------------

    /**
     * Cria o cliente empresa na BlindPay. A conta fica bloqueada durante o envio para
     * que submissões concorrentes não criem dois clientes; a chave de idempotência
     * deriva do corpo, então repetir o mesmo envio após uma falha de rede reaproveita
     * a resposta original da BlindPay.
     */
    @Transactional(noRollbackFor = BlindPayIntegrationException.class)
    public BlindPayComplianceStatusResponse submitKyb(UserEntity currentUser, BlindPayKybRequest request) {
        CompanyEntity company = company(currentUser);
        ProviderAccountEntity account = providerAccountRepository
                .findForUpdate(company.getId(), IntegrationProvider.BLINDPAY)
                .filter(found -> found.getTosId() != null && found.getTosAcceptedAt() != null)
                .orElseThrow(() -> new UnprocessableEntityException(
                        "Accept the BlindPay terms of service before submitting the KYB"));
        if (account.getExternalCustomerId() != null) {
            throw new ConflictException("The KYB was already submitted to BlindPay; update it instead");
        }
        validateBusinessRules(request, false);

        BlindPayCreateCustomerRequest customerRequest = createCustomerRequest(company, account, request);
        OffsetDateTime now = now();
        String customerId;
        try {
            BlindPayCustomerCreatedResponse created = blindPayGateway.createCustomer(
                    customerRequest, idempotencyKey("company:" + company.getId() + ":blindpay:kyb:", customerRequest));
            customerId = created.customerId() != null && !created.customerId().isBlank()
                    ? created.customerId()
                    : created.id();
            if (customerId == null || customerId.isBlank()) {
                throw BlindPayIntegrationException.invalidResponse("BlindPay customer response has no id");
            }
        } catch (BlindPayIntegrationException ex) {
            account.setStatus(BlindPayOnboardingStatus.SUBMISSION_FAILED.name());
            account.setLastErrorCode(errorCode(ex));
            account.setUpdatedAt(now);
            providerAccountRepository.save(account);
            throw ex;
        }

        account.setExternalCustomerId(customerId);
        account.setStatus(BlindPayOnboardingStatus.VERIFYING.name());
        account.setLastErrorCode(null);
        account.setUpdatedAt(now);
        providerAccountRepository.save(account);
        return toResponse(account, synchronizeQuietly(account), false);
    }

    /**
     * Atualiza o cliente já criado. Cliente rejeitado não pode ser atualizado: na
     * BlindPay a atualização não reinicia a verificação.
     */
    @Transactional(noRollbackFor = BlindPayIntegrationException.class)
    public BlindPayComplianceStatusResponse updateKyb(UserEntity currentUser, BlindPayKybRequest request) {
        CompanyEntity company = company(currentUser);
        ProviderAccountEntity account = providerAccountRepository
                .findForUpdate(company.getId(), IntegrationProvider.BLINDPAY)
                .filter(found -> found.getExternalCustomerId() != null)
                .orElseThrow(() -> new UnprocessableEntityException("Submit the KYB to BlindPay before updating it"));
        BlindPayOnboardingStatus status = BlindPayOnboardingStatus.parse(account.getStatus());
        if (status != null && status.isTerminalRejection()) {
            throw new ConflictException(
                    "BlindPay rejected this company; updating the data does not restart the verification");
        }
        validateBusinessRules(request, true);

        BlindPayUpdateCustomerRequest updateRequest = updateCustomerRequest(company, account, request);
        try {
            blindPayGateway.updateCustomer(account.getExternalCustomerId(), updateRequest,
                    idempotencyKey("company:" + company.getId() + ":blindpay:kyb-update:", updateRequest));
        } catch (BlindPayIntegrationException ex) {
            account.setLastErrorCode(errorCode(ex));
            account.setUpdatedAt(now());
            providerAccountRepository.save(account);
            throw ex;
        }
        account.setLastErrorCode(null);
        account.setUpdatedAt(now());
        providerAccountRepository.save(account);
        return toResponse(account, synchronizeQuietly(account), false);
    }

    // ---------------------------------------------------------------------
    // Status
    // ---------------------------------------------------------------------

    /**
     * Consulta o cliente na BlindPay (fallback dos webhooks) e devolve o status. Se a
     * BlindPay estiver indisponível, devolve o último status persistido.
     */
    public BlindPayComplianceStatusResponse getStatus(UserEntity currentUser) {
        ProviderAccountEntity account = account(company(currentUser));
        if (account == null || account.getExternalCustomerId() == null) {
            return toResponse(account, null, false);
        }
        try {
            return toResponse(account, synchronize(account), false);
        } catch (BlindPayIntegrationException ex) {
            log.warn("BlindPay compliance synchronization failed: account={}, code={}",
                    account.getId(), ex.getProviderErrorCode());
            return toResponse(account, null, true);
        }
    }

    // ---------------------------------------------------------------------
    // RFI
    // ---------------------------------------------------------------------

    public BlindPayRfiDetailsResponse getRfi(UserEntity currentUser) {
        ProviderAccountEntity account = submittedAccount(currentUser);
        BlindPayRfiResponse rfi = pendingRfi(account);
        if (rfi == null) {
            return BlindPayRfiDetailsResponse.none();
        }
        List<BlindPayRfiDetailsResponse.Section> sections = rfi.request() == null ? List.of() : rfi.request().stream()
                .map(section -> new BlindPayRfiDetailsResponse.Section(
                        section.title(),
                        section.description(),
                        section.supportingDocument(),
                        section.fields() == null ? List.of() : section.fields().stream()
                                .map(field -> new BlindPayRfiDetailsResponse.Field(
                                        field.key(),
                                        field.label(),
                                        Boolean.TRUE.equals(field.required()),
                                        Boolean.TRUE.equals(field.multiple()),
                                        field.aipriseDocumentType(),
                                        field.items() == null ? List.of() : field.items().stream()
                                                .map(item -> new BlindPayRfiDetailsResponse.Option(
                                                        item.label(), item.value()))
                                                .toList()))
                                .toList()))
                .toList();
        return new BlindPayRfiDetailsResponse(true, rfi.id(), rfi.expiresAt(), sections);
    }

    /**
     * Responde o RFI aberto. As chaves precisam ser campos do RFI, os obrigatórios
     * precisam estar presentes e documentos são URLs https (até 20 por campo).
     */
    public BlindPayComplianceStatusResponse submitRfi(UserEntity currentUser, Map<String, Object> responses) {
        ProviderAccountEntity account = submittedAccount(currentUser);
        BlindPayRfiResponse rfi = pendingRfi(account);
        if (rfi == null) {
            throw new ConflictException("BlindPay has no pending information request for this company");
        }
        Map<String, Object> normalized = validateRfiResponses(rfi, responses);

        BlindPayRfiSubmitResponse result = blindPayGateway.submitCustomerRfi(
                account.getExternalCustomerId(), normalized, idempotencyKey("rfi:" + rfi.id() + ":", normalized));
        if (result == null || !result.succeeded()) {
            String reason = result == null ? null : result.reason();
            throw new ConflictException("BlindPay did not accept the information request response"
                    + (reason == null ? "" : ": " + reason));
        }
        return toResponse(account, synchronizeQuietly(account), false);
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private BlindPayCustomerResponse synchronize(ProviderAccountEntity account) {
        BlindPayCustomerResponse customer = blindPayGateway.getCustomer(account.getExternalCustomerId());
        if (customer == null || !BlindPayAccountState.belongsTo(
                account, customer.id(), customer.externalId(), customer.instanceId(), properties.getInstanceId())) {
            throw BlindPayIntegrationException.invalidResponse(
                    "BlindPay returned a customer that does not belong to this company");
        }
        if (BlindPayAccountState.apply(account, customer.kycStatus(), customer.amlStatus(),
                customer.isTosAccepted(), customer.tosId(), customer.updatedAt(), now())) {
            providerAccountRepository.save(account);
        }
        return customer;
    }

    /** Sincroniza após uma escrita sem falhar a operação, que já foi aceita pela BlindPay. */
    private BlindPayCustomerResponse synchronizeQuietly(ProviderAccountEntity account) {
        try {
            return synchronize(account);
        } catch (BlindPayIntegrationException ex) {
            log.warn("BlindPay compliance synchronization failed: account={}, code={}",
                    account.getId(), ex.getProviderErrorCode());
            return null;
        }
    }

    private BlindPayRfiResponse pendingRfi(ProviderAccountEntity account) {
        BlindPayRfiResponse rfi = blindPayGateway.getCustomerRfi(account.getExternalCustomerId());
        if (rfi == null || !RFI_PENDING.equalsIgnoreCase(rfi.status())) {
            return null;
        }
        if (rfi.customerId() != null && !rfi.customerId().equals(account.getExternalCustomerId())) {
            throw BlindPayIntegrationException.invalidResponse(
                    "BlindPay returned an information request of another customer");
        }
        return rfi;
    }

    private Map<String, Object> validateRfiResponses(BlindPayRfiResponse rfi, Map<String, Object> responses) {
        if (responses == null || responses.isEmpty()) {
            throw new IllegalArgumentException("responses must not be empty");
        }
        Map<String, BlindPayRfiResponse.Field> fields = new TreeMap<>();
        if (rfi.request() != null) {
            rfi.request().stream()
                    .filter(section -> section.fields() != null)
                    .flatMap(section -> section.fields().stream())
                    .filter(field -> field.key() != null)
                    .forEach(field -> fields.put(field.key(), field));
        }

        Map<String, Object> normalized = new TreeMap<>();
        for (Map.Entry<String, Object> entry : responses.entrySet()) {
            BlindPayRfiResponse.Field field = fields.get(entry.getKey());
            if (field == null) {
                throw new IllegalArgumentException("responses." + entry.getKey() + ": is not requested by BlindPay");
            }
            normalized.put(entry.getKey(), rfiValue(entry.getKey(), entry.getValue(), field));
        }
        for (BlindPayRfiResponse.Field field : fields.values()) {
            if (Boolean.TRUE.equals(field.required()) && !normalized.containsKey(field.key())) {
                throw new IllegalArgumentException("responses." + field.key() + ": must be provided");
            }
        }
        return normalized;
    }

    private Object rfiValue(String key, Object value, BlindPayRfiResponse.Field field) {
        if (value instanceof String text) {
            if (text.isBlank() || text.length() > MAX_RFI_TEXT_LENGTH) {
                throw new IllegalArgumentException(
                        "responses." + key + ": must have between 1 and " + MAX_RFI_TEXT_LENGTH + " characters");
            }
            if (field.items() != null && !field.items().isEmpty()
                    && field.items().stream().noneMatch(item -> text.equals(item.value()))) {
                throw new IllegalArgumentException("responses." + key + ": is not an accepted option");
            }
            return text;
        }
        if (value instanceof List<?> files) {
            if (files.isEmpty() || files.size() > MAX_RFI_FILES) {
                throw new IllegalArgumentException(
                        "responses." + key + ": must have between 1 and " + MAX_RFI_FILES + " files");
            }
            List<String> urls = new ArrayList<>();
            for (Object file : files) {
                if (!(file instanceof String url) || !url.startsWith(HTTPS_PREFIX) || url.length() > 2048) {
                    throw new IllegalArgumentException("responses." + key + ": files must be https URLs");
                }
                urls.add(url);
            }
            return List.copyOf(urls);
        }
        throw new IllegalArgumentException("responses." + key + ": must be a text or a list of file URLs");
    }

    private void validateBusinessRules(BlindPayKybRequest request, boolean update) {
        if ("other".equals(request.accountPurpose()) && isBlank(request.accountPurposeOther())) {
            throw new IllegalArgumentException("accountPurposeOther: must be provided when accountPurpose is other");
        }
        if ("other".equals(request.purposeOfTransactions()) && isBlank(request.purposeOfTransactionsExplanation())) {
            throw new IllegalArgumentException(
                    "purposeOfTransactionsExplanation: must be provided when purposeOfTransactions is other");
        }
        if (request.owners().stream().map(BlindPayKybRequest.Owner::role).noneMatch(CONTROLLING_ROLES::contains)) {
            throw new IllegalArgumentException("owners: at least one controlling person must be provided");
        }

        int totalOwnership = 0;
        for (int index = 0; index < request.owners().size(); index++) {
            BlindPayKybRequest.Owner owner = request.owners().get(index);
            String prefix = "owners[" + index + "].";
            if (update && isBlank(owner.id())) {
                throw new IllegalArgumentException(prefix + "id: must be provided to update an owner");
            }
            if (!update && owner.id() != null) {
                throw new IllegalArgumentException(prefix + "id: must not be provided on the first submission");
            }
            boolean beneficialOwner = owner.role() != BlindPayApi.Customer.OwnerRole.controlling_person;
            if (beneficialOwner && (owner.ownershipPercentage() == null
                    || owner.ownershipPercentage() < MIN_BENEFICIAL_OWNERSHIP)) {
                throw new IllegalArgumentException(
                        prefix + "ownershipPercentage: beneficial owners must hold at least 25%");
            }
            if ("US".equals(owner.country()) && owner.taxType() == null) {
                throw new IllegalArgumentException(prefix + "taxType: must be provided for US owners");
            }
            totalOwnership += owner.ownershipPercentage() == null ? 0 : owner.ownershipPercentage();
        }
        if (totalOwnership > 100) {
            throw new IllegalArgumentException("owners: ownership percentages must not exceed 100%");
        }
    }

    private BlindPayCreateCustomerRequest createCustomerRequest(
            CompanyEntity company, ProviderAccountEntity account, BlindPayKybRequest request) {
        return BlindPayCreateCustomerRequest.builder()
                .type(BlindPayApi.Customer.Type.business)
                .kycType(BlindPayApi.Customer.KycType.standard)
                .email(request.email())
                .taxId(digits(company.getCnpj()))
                .addressLine1(request.addressLine1())
                .addressLine2(request.addressLine2())
                .city(request.city())
                .stateProvinceRegion(request.stateProvinceRegion())
                .country(request.country())
                .postalCode(request.postalCode())
                .phoneNumber(request.phoneNumber())
                .legalName(request.legalName())
                .alternateName(request.alternateName())
                .formationDate(request.formationDate())
                .website(request.website())
                .businessType(request.businessType())
                .businessDescription(request.businessDescription())
                .businessIndustry(request.businessIndustry())
                .estimatedAnnualRevenue(request.estimatedAnnualRevenue())
                .sourceOfWealth(request.sourceOfWealth())
                .incorporationDocFile(request.incorporationDocFile())
                .proofOfOwnershipDocFile(request.proofOfOwnershipDocFile())
                .proofOfAddressDocType(request.proofOfAddressDocType())
                .proofOfAddressDocFile(request.proofOfAddressDocFile())
                .sourceOfFundsDocType(request.sourceOfFundsDocType())
                .sourceOfFundsDocFile(request.sourceOfFundsDocFile())
                .purposeOfTransactions(request.purposeOfTransactions())
                .purposeOfTransactionsExplanation(request.purposeOfTransactionsExplanation())
                .accountPurpose(request.accountPurpose())
                .accountPurposeOther(request.accountPurposeOther())
                .publiclyTraded(request.publiclyTraded())
                .owners(owners(request))
                .externalId(company.getId().toString())
                .tosId(account.getTosId())
                .build();
    }

    private BlindPayUpdateCustomerRequest updateCustomerRequest(
            CompanyEntity company, ProviderAccountEntity account, BlindPayKybRequest request) {
        return BlindPayUpdateCustomerRequest.builder()
                .email(request.email())
                .taxId(digits(company.getCnpj()))
                .addressLine1(request.addressLine1())
                .addressLine2(request.addressLine2())
                .city(request.city())
                .stateProvinceRegion(request.stateProvinceRegion())
                .country(request.country())
                .postalCode(request.postalCode())
                .phoneNumber(request.phoneNumber())
                .legalName(request.legalName())
                .alternateName(request.alternateName())
                .formationDate(request.formationDate())
                .website(request.website())
                .businessType(request.businessType())
                .businessDescription(request.businessDescription())
                .businessIndustry(request.businessIndustry())
                .estimatedAnnualRevenue(request.estimatedAnnualRevenue())
                .sourceOfWealth(request.sourceOfWealth())
                .incorporationDocFile(request.incorporationDocFile())
                .proofOfOwnershipDocFile(request.proofOfOwnershipDocFile())
                .proofOfAddressDocType(request.proofOfAddressDocType())
                .proofOfAddressDocFile(request.proofOfAddressDocFile())
                .sourceOfFundsDocType(request.sourceOfFundsDocType())
                .sourceOfFundsDocFile(request.sourceOfFundsDocFile())
                .purposeOfTransactions(request.purposeOfTransactions())
                .purposeOfTransactionsExplanation(request.purposeOfTransactionsExplanation())
                .accountPurpose(request.accountPurpose())
                .accountPurposeOther(request.accountPurposeOther())
                .publiclyTraded(request.publiclyTraded())
                .owners(owners(request))
                .externalId(company.getId().toString())
                .tosId(account.getTosId())
                .build();
    }

    private List<BlindPayCreateCustomerRequest.Owner> owners(BlindPayKybRequest request) {
        return request.owners().stream()
                .map(owner -> BlindPayCreateCustomerRequest.Owner.builder()
                        .id(owner.id())
                        .role(owner.role())
                        .firstName(owner.firstName())
                        .lastName(owner.lastName())
                        .dateOfBirth(owner.dateOfBirth())
                        .taxId(owner.taxId())
                        .taxType(owner.taxType())
                        .addressLine1(owner.addressLine1())
                        .addressLine2(owner.addressLine2())
                        .city(owner.city())
                        .stateProvinceRegion(owner.stateProvinceRegion())
                        .country(owner.country())
                        .postalCode(owner.postalCode())
                        .idDocCountry(owner.idDocCountry())
                        .idDocType(owner.idDocType())
                        .idDocFrontFile(owner.idDocFrontFile())
                        .idDocBackFile(owner.idDocBackFile())
                        .proofOfAddressDocType(owner.proofOfAddressDocType())
                        .proofOfAddressDocFile(owner.proofOfAddressDocFile())
                        .ownershipPercentage(owner.ownershipPercentage())
                        .title(owner.title())
                        .build())
                .toList();
    }

    private BlindPayComplianceStatusResponse toResponse(
            ProviderAccountEntity account, BlindPayCustomerResponse customer, boolean synchronizationFailed) {
        if (account == null) {
            return new BlindPayComplianceStatusResponse(BlindPayOnboardingStatus.NOT_STARTED,
                    false, false, false, false, false, null, null, List.of(), List.of(), null, null, false);
        }
        boolean submitted = account.getExternalCustomerId() != null;
        boolean termsAccepted = account.getTosAcceptedAt() != null;
        BlindPayOnboardingStatus status = Objects.requireNonNullElse(
                BlindPayOnboardingStatus.parse(account.getStatus()),
                submitted ? BlindPayOnboardingStatus.VERIFYING : BlindPayOnboardingStatus.TOS_PENDING);

        List<String> warningCodes = customer == null || customer.kycWarnings() == null ? List.of()
                : customer.kycWarnings().stream()
                    .map(BlindPayCustomerResponse.KycWarning::code)
                    .filter(Objects::nonNull)
                    .toList();
        List<BlindPayComplianceStatusResponse.OwnerReference> owners =
                customer == null || customer.owners() == null ? List.of()
                : customer.owners().stream()
                    .map(owner -> new BlindPayComplianceStatusResponse.OwnerReference(
                            owner.id(), owner.role(), owner.firstName(), owner.lastName()))
                    .toList();

        return new BlindPayComplianceStatusResponse(
                status,
                status == BlindPayOnboardingStatus.APPROVED,
                termsAccepted,
                submitted,
                termsAccepted && !submitted,
                submitted && !status.isTerminalRejection(),
                account.getKycStatus(),
                account.getAmlStatus(),
                warningCodes,
                owners,
                account.getLastErrorCode(),
                account.getSynchronizedAt(),
                synchronizationFailed);
    }

    private ProviderAccountEntity findOrCreateAccount(CompanyEntity company) {
        ProviderAccountEntity existing = account(company);
        if (existing != null) {
            return existing;
        }
        OffsetDateTime now = now();
        try {
            return providerAccountRepository.save(ProviderAccountEntity.builder()
                    .company(company)
                    .provider(IntegrationProvider.BLINDPAY)
                    .status(BlindPayOnboardingStatus.TOS_PENDING.name())
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        } catch (DataIntegrityViolationException ex) {
            return providerAccountRepository.findByCompanyIdAndProvider(company.getId(), IntegrationProvider.BLINDPAY)
                    .orElseThrow(() -> ex);
        }
    }

    private ProviderAccountEntity submittedAccount(UserEntity currentUser) {
        ProviderAccountEntity account = account(company(currentUser));
        if (account == null || account.getExternalCustomerId() == null) {
            throw new UnprocessableEntityException("The KYB was not submitted to BlindPay yet");
        }
        return account;
    }

    private ProviderAccountEntity account(CompanyEntity company) {
        return providerAccountRepository.findByCompanyIdAndProvider(company.getId(), IntegrationProvider.BLINDPAY)
                .orElse(null);
    }

    private CompanyEntity company(UserEntity currentUser) {
        if (currentUser == null || currentUser.getCompanyId() == null) {
            throw new NotFoundException("Company not found");
        }
        return companyRepository.findById(currentUser.getCompanyId())
                .orElseThrow(() -> new NotFoundException("Company not found"));
    }

    /**
     * Chave estável para o mesmo conteúdo: uma retentativa idêntica reaproveita a
     * resposta da BlindPay, e dados corrigidos geram uma nova requisição.
     */
    private String idempotencyKey(String prefix, Object body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(body));
            return prefix + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private String errorCode(BlindPayIntegrationException ex) {
        if (ex.getProviderErrorCode() != null) {
            return ex.getProviderErrorCode();
        }
        return ex.isRetryable() ? "PROVIDER_UNAVAILABLE" : "PROVIDER_ERROR";
    }

    private String digits(String value) {
        return value == null ? null : value.replaceAll("\\D", "");
    }

    private String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    /** Formatos aceitos pela BlindPay, reconhecidos pela assinatura do arquivo. */
    private enum DocumentFormat {
        PDF("application/pdf", "pdf", false, new int[]{0x25, 0x50, 0x44, 0x46}),
        PNG("image/png", "png", true, new int[]{0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}),
        JPEG("image/jpeg", "jpg", true, new int[]{0xFF, 0xD8, 0xFF});

        private final String contentType;
        private final String extension;
        private final boolean image;
        private final int[] signature;

        DocumentFormat(String contentType, String extension, boolean image, int[] signature) {
            this.contentType = contentType;
            this.extension = extension;
            this.image = image;
            this.signature = signature;
        }

        static DocumentFormat detect(byte[] content) {
            for (DocumentFormat format : values()) {
                if (format.matches(content)) {
                    return format;
                }
            }
            return null;
        }

        private boolean matches(byte[] content) {
            if (content.length < signature.length) {
                return false;
            }
            for (int index = 0; index < signature.length; index++) {
                if ((content[index] & 0xFF) != signature[index]) {
                    return false;
                }
            }
            return true;
        }

        String contentType() {
            return contentType;
        }

        String extension() {
            return extension;
        }

        boolean image() {
            return image;
        }
    }
}
