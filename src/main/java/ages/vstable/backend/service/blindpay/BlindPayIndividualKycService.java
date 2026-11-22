package ages.vstable.backend.service.blindpay;

import ages.vstable.backend.configuration.security.CompanyAccess;
import ages.vstable.backend.dto.blindpay.BlindPayIndividualKycRequest;
import ages.vstable.backend.dto.blindpay.BlindPayIndividualKycStatus;
import ages.vstable.backend.dto.blindpay.BlindPayDocumentUploadResponse;
import ages.vstable.backend.dto.blindpay.BlindPayRfiDetailsResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayRfiResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayRfiSubmitResponse;
import ages.vstable.backend.entity.BlindPayIndividualKycEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.exception.BlindPayIntegrationException;
import ages.vstable.backend.external.blindpay.BlindPayApi;
import ages.vstable.backend.external.blindpay.BlindPayGateway;
import ages.vstable.backend.external.blindpay.dto.BlindPayCreateCustomerRequest;
import ages.vstable.backend.external.blindpay.dto.BlindPayCustomerCreatedResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayCustomerResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayInitiateTosRequest;
import ages.vstable.backend.external.blindpay.dto.BlindPayInitiateTosResponse;
import ages.vstable.backend.repository.BlindPayIndividualKycRepository;
import ages.vstable.backend.repository.ProviderAccountRepository;
import ages.vstable.backend.entity.enums.IntegrationProvider;
import ages.vstable.backend.entity.enums.BlindPayOnboardingStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** KYC Standard individual, segregado do KYB empresarial e desativado por padrão. */
@Service
@RequiredArgsConstructor
public class BlindPayIndividualKycService {
    static final int MAX_FILE_BYTES = 3 * 1024 * 1024;
    private static final String REJECTED = "rejected";
    private final BlindPayGateway gateway;
    private final BlindPayIndividualKycRepository repository;
    private final ProviderAccountRepository providerAccountRepository;

    @Value("${blindpay.individual-kyc.enabled:false}")
    private boolean enabled;

    @Value("${blindpay.individual-kyc.redirect-url:}")
    private String redirectUrl;

    private void verifyUser(UserEntity user) {
        if (!enabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Individual KYC is disabled for this environment");
        if (user == null || user.getId() == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        CompanyAccess.rejectRevokedMembership(user);
    }

    public BlindPayInitiateTosResponse startTerms(UserEntity user) {
        verifyUser(user);
        BlindPayIndividualKycEntity entry = repository.findById(user.getId())
                .orElseGet(() -> BlindPayIndividualKycEntity.builder()
                        .userId(user.getId()).createdAt(now()).updatedAt(now()).build());
        if (entry.getCustomerId() != null || entry.getTosAcceptedAt() != null)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "KYC already submitted or BlindPay terms already accepted");

        // A pending (never accepted) session is replaced: the user may have closed the
        // hosted page. BlindPay rejects reused idempotency keys, so each link gets a new
        // key and only the latest one can confirm acceptance through tos.accept.
        UUID sessionKey = UUID.randomUUID();
        entry.setTosSessionKey(sessionKey);
        entry.setUpdatedAt(now());
        // Persist the session before exposing the hosted link so its redirect can be
        // correlated with this user even when they accept immediately.
        repository.saveAndFlush(entry);

        BlindPayInitiateTosResponse response = gateway.initiateTermsOfService(
                new BlindPayInitiateTosRequest(sessionKey, null,
                        redirectUrl == null || redirectUrl.isBlank() ? null
                                : UriComponentsBuilder.fromUriString(redirectUrl)
                                    .replaceQueryParam("state", sessionKey).build().toUriString()));
        if (response == null || !trustedTermsUrl(response.url()))
            throw BlindPayIntegrationException.invalidResponse("Invalid BlindPay terms link");
        return response;
    }

    private static boolean trustedTermsUrl(String url) {
        try {
            URI uri = URI.create(url);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && "app.blindpay.com".equalsIgnoreCase(uri.getHost());
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** Consumes the hosted redirect for this user's latest terms session. Customer creation
     * still sends tos_id to BlindPay, which validates that the acceptance is valid and unused. */
    @Transactional
    public BlindPayIndividualKycStatus confirmTerms(UserEntity user, UUID sessionKey, String tosId) {
        verifyUser(user);
        BlindPayIndividualKycEntity entry = repository.findForUpdate(user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Start the terms session first"));
        if (sessionKey == null || !sessionKey.equals(entry.getTosSessionKey()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Terms session does not match this user");
        if (tosId == null || !tosId.matches("to_[A-Za-z0-9]{12}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid terms acceptance id");
        if (entry.getCustomerId() != null || (entry.getTosId() != null && !tosId.equals(entry.getTosId())))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Terms already linked to a customer or acceptance");
        if (entry.getTosAcceptedAt() == null) {
            entry.setTosId(tosId);
            entry.setTosAcceptedAt(now());
            entry.setUpdatedAt(now());
            repository.save(entry);
        }
        return new BlindPayIndividualKycStatus(true, false, entry.getKycStatus(), entry.getAmlStatus());
    }

    @Transactional
    public BlindPayIndividualKycStatus status(UserEntity user) {
        verifyUser(user);
        Optional<BlindPayIndividualKycEntity> found = repository.findById(user.getId());
        if (found.isEmpty()) return new BlindPayIndividualKycStatus(false, false, null, null);
        BlindPayIndividualKycEntity entry = found.get();
        if (entry.getCustomerId() != null) {
            BlindPayCustomerResponse remote = gateway.getCustomer(entry.getCustomerId());
            if (remote == null || !entry.getCustomerId().equals(remote.id())
                    || !("user:" + user.getId()).equals(remote.externalId())) {
                throw BlindPayIntegrationException.invalidResponse("BlindPay returned an unexpected customer");
            }
            if (remote.updatedAt() != null && entry.getProviderUpdatedAt() != null
                    && remote.updatedAt().isBefore(entry.getProviderUpdatedAt())) {
                throw BlindPayIntegrationException.invalidResponse("BlindPay returned an outdated customer status");
            }
            entry.setKycStatus(remote.kycStatus());
            entry.setAmlStatus(remote.amlStatus());
            entry.setProviderUpdatedAt(remote.updatedAt());
            entry.setUpdatedAt(now());
            repository.save(entry);
            providerAccountRepository.findByProviderAndExternalCustomerId(
                    IntegrationProvider.BLINDPAY, entry.getCustomerId()).ifPresent(account -> {
                if (account.getProviderUpdatedAt() != null && remote.updatedAt() != null
                        && remote.updatedAt().isBefore(account.getProviderUpdatedAt())) return;
                account.setKycStatus(remote.kycStatus());
                account.setAmlStatus(remote.amlStatus());
                account.setStatus(BlindPayOnboardingStatus.fromProvider(remote.kycStatus(), remote.amlStatus(),
                        account.getTosAcceptedAt() != null).name());
                account.setProviderUpdatedAt(remote.updatedAt());
                account.setSynchronizedAt(now());
                account.setUpdatedAt(now());
                providerAccountRepository.save(account);
            });
        }
        return new BlindPayIndividualKycStatus(entry.getTosAcceptedAt() != null,
                entry.getCustomerId() != null, entry.getKycStatus(), entry.getAmlStatus());
    }

    @Transactional
    public BlindPayIndividualKycStatus submit(UserEntity user, BlindPayIndividualKycRequest data,
            MultipartFile idFront, MultipartFile idBack, MultipartFile selfie,
            MultipartFile proofAddress) throws IOException {
        verifyUser(user);
        BlindPayIndividualKycEntity entry = repository.findForUpdate(user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Accept BlindPay terms before submitting"));
        if (entry.getTosAcceptedAt() == null || entry.getTosId() == null)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "BlindPay hosted terms not accepted");
        if (entry.getCustomerId() != null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "KYC already submitted");
        if (!"BR".equals(data.country()) || !"BR".equals(data.idDocCountry()))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Only Brazilian Standard KYC is supported");
        if (!validCpf(data.taxId()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid CPF");
        if (data.idDocType() != BlindPayApi.Customer.IdDocType.PASSPORT && missing(idBack))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload ID front and back");
        boolean hasProof = !missing(proofAddress);
        if (hasProof != (data.proofOfAddressDocType() != null
                && !data.proofOfAddressDocType().isBlank()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Proof of address type and file must be provided together");

        // Validate the full group before any network upload. No document bytes are persisted.
        Document front = inspect(idFront, true);
        Document back = missing(idBack) ? null : inspect(idBack, true);
        Document face = inspect(selfie, true);
        Document proof = hasProof ? inspect(proofAddress, false) : null;

        String frontUrl = upload("id-front", front);
        String backUrl = back == null ? null : upload("id-back", back);
        String selfieUrl = upload("selfie", face);
        String proofUrl = proof == null ? null : upload("proof-address", proof);
        BlindPayCreateCustomerRequest payload = BlindPayCreateCustomerRequest.builder()
                .type(BlindPayApi.Customer.Type.individual)
                .kycType(BlindPayApi.Customer.KycType.standard)
                .email(user.getEmail())
                .firstName(data.firstName())
                .lastName(data.lastName())
                .dateOfBirth(data.dateOfBirth())
                .taxId(data.taxId())
                .phoneNumber(data.phoneNumber())
                .country("BR")
                .addressLine1(data.addressLine1())
                .addressLine2(data.addressLine2())
                .city(data.city())
                .stateProvinceRegion(data.stateProvinceRegion())
                .postalCode(data.postalCode())
                .idDocCountry("BR")
                .idDocType(data.idDocType())
                .idDocFrontFile(frontUrl)
                .idDocBackFile(backUrl)
                .selfieFile(selfieUrl)
                .proofOfAddressDocType(data.proofOfAddressDocType())
                .proofOfAddressDocFile(proofUrl)
                .tosId(entry.getTosId())
                .externalId("user:" + user.getId())
                .build();

        // Stable per person and acceptance. No automatic retry after ambiguous provider failure.
        String idempotencyKey = "individual:" + user.getId() + ":" + entry.getTosId();
        BlindPayCustomerCreatedResponse response = gateway.createCustomer(payload, idempotencyKey);
        String id = response == null ? null : response.customerId() != null
                ? response.customerId() : response.id();
        if (id == null || !id.startsWith("re_"))
            throw BlindPayIntegrationException.invalidResponse("BlindPay customer id was not returned");
        entry.setCustomerId(id);
        entry.setKycStatus("verifying");
        entry.setUpdatedAt(now());
        repository.save(entry);
        return new BlindPayIndividualKycStatus(true, true, "verifying", entry.getAmlStatus());
    }

    /**
     * Novo cadastro após recusa: a BlindPay não permite editar um cliente recusado, então
     * o próximo envio cria outro cliente, com novo aceite dos termos. O cliente recusado
     * fica registrado para que seus webhooks atrasados sejam ignorados.
     */
    @Transactional
    public BlindPayIndividualKycStatus restart(UserEntity user) {
        verifyUser(user);
        BlindPayIndividualKycEntity entry = repository.findForUpdate(user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "No KYC to restart"));
        if (entry.getCustomerId() == null || !REJECTED.equalsIgnoreCase(entry.getKycStatus()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a rejected KYC can be restarted");
        entry.setSupersededCustomerId(entry.getCustomerId());
        entry.setCustomerId(null);
        entry.setTosId(null);
        entry.setTosSessionKey(null);
        entry.setTosAcceptedAt(null);
        entry.setKycStatus(null);
        entry.setAmlStatus(null);
        entry.setProviderUpdatedAt(null);
        entry.setUpdatedAt(now());
        repository.save(entry);
        return new BlindPayIndividualKycStatus(false, false, null, null);
    }

    /** Pedido de informações (RFI) aberto pela BlindPay para o cliente do titular. */
    public BlindPayRfiDetailsResponse rfi(UserEntity user) {
        BlindPayIndividualKycEntity entry = submitted(user);
        return BlindPayRfiSupport.toDetails(BlindPayRfiSupport.pending(gateway, entry.getCustomerId()));
    }

    public BlindPayIndividualKycStatus submitRfi(UserEntity user, Map<String, Object> responses) {
        BlindPayIndividualKycEntity entry = submitted(user);
        BlindPayRfiResponse rfi = BlindPayRfiSupport.pending(gateway, entry.getCustomerId());
        if (rfi == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "BlindPay has no pending information request");
        Map<String, Object> normalized = BlindPayRfiSupport.validateResponses(rfi, responses);
        BlindPayRfiSubmitResponse result = gateway.submitCustomerRfi(entry.getCustomerId(), normalized,
                "individual-rfi:" + rfi.id() + ":" + sha256(normalized.toString()));
        if (result == null || !result.succeeded())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "BlindPay did not accept the information request response");
        return status(user);
    }

    /** Envia um documento pedido no RFI e devolve a URL a ser usada na resposta. */
    public BlindPayDocumentUploadResponse uploadRfiDocument(UserEntity user, MultipartFile file) throws IOException {
        submitted(user);
        return new BlindPayDocumentUploadResponse(upload("rfi", inspect(file, false)));
    }

    private BlindPayIndividualKycEntity submitted(UserEntity user) {
        verifyUser(user);
        return repository.findById(user.getId())
                .filter(entry -> entry.getCustomerId() != null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "KYC not submitted"));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private String upload(String prefix, Document document) {
        return gateway.uploadFile(BlindPayApi.Upload.Bucket.onboarding,
                prefix + "." + document.extension(), document.contentType(), document.bytes()).fileUrl();
    }

    private static boolean missing(MultipartFile file) {
        return file == null || file.isEmpty();
    }

    private static Document inspect(MultipartFile file, boolean imageOnly) throws IOException {
        if (missing(file))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required document");
        if (file.getSize() > MAX_FILE_BYTES)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "File exceeds 3 MB");
        byte[] bytes = file.getBytes();
        if (bytes.length == 0 || bytes.length > MAX_FILE_BYTES)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid file size");
        if (bytes.length >= 3 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216
                && (bytes[2] & 255) == 255)
            return new Document(bytes, "jpg", "image/jpeg");
        if (bytes.length >= 8 && (bytes[0] & 255) == 137 && bytes[1] == 80
                && bytes[2] == 78 && bytes[3] == 71 && bytes[4] == 13
                && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10)
            return new Document(bytes, "png", "image/png");
        if (!imageOnly && bytes.length >= 5 && bytes[0] == '%' && bytes[1] == 'P'
                && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-')
            return new Document(bytes, "pdf", "application/pdf");
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                imageOnly ? "JPEG or PNG required" : "PDF, JPEG or PNG required");
    }

    private record Document(byte[] bytes, String extension, String contentType) {}

    static boolean validCpf(String cpf) {
        if (cpf == null || !cpf.matches("\\d{11}") || cpf.chars().distinct().count() == 1) return false;
        for (int len = 9; len <= 10; len++) {
            int sum = 0;
            for (int i = 0; i < len; i++) sum += (cpf.charAt(i) - '0') * (len + 1 - i);
            int digit = (sum * 10) % 11;
            if ((digit == 10 ? 0 : digit) != cpf.charAt(len) - '0') return false;
        }
        return true;
    }

    /** Only the verified Svix webhook service calls these methods. */
    @Transactional
    public boolean acceptTerms(UUID key, String tosId, OffsetDateTime timestamp) {
        BlindPayIndividualKycEntity entry = repository.findByTosSessionKey(key).orElse(null);
        if (entry == null || entry.getTosAcceptedAt() != null || entry.getCustomerId() != null
                || tosId == null || !tosId.startsWith("to_")) return false;
        entry.setTosId(tosId);
        entry.setTosAcceptedAt(timestamp);
        entry.setUpdatedAt(timestamp);
        repository.save(entry);
        return true;
    }

    @Transactional
    public boolean updateFromWebhook(String customerId, String externalId,
            String kycStatus, String amlStatus, OffsetDateTime providerUpdatedAt, OffsetDateTime now) {
        BlindPayIndividualKycEntity entry = repository.findByCustomerId(customerId).orElse(null);
        if (entry == null && externalId != null && externalId.startsWith("user:")) {
            try {
                UUID userId = UUID.fromString(externalId.substring(5));
                entry = repository.findById(userId).orElse(null);
            } catch (IllegalArgumentException ignored) {
                return false;
            }
        }
        if (entry == null || (entry.getCustomerId() != null
                && !entry.getCustomerId().equals(customerId))) return false;
        // Cliente recusado e substituído por um novo cadastro: nunca volta a ser associado.
        if (customerId.equals(entry.getSupersededCustomerId())) return false;
        if (externalId != null && !externalId.equals("user:" + entry.getUserId())) return false;
        if (entry.getProviderUpdatedAt() != null && providerUpdatedAt != null
                && providerUpdatedAt.isBefore(entry.getProviderUpdatedAt())) return false;
        if (entry.getCustomerId() == null) entry.setCustomerId(customerId);
        if (kycStatus != null) entry.setKycStatus(kycStatus);
        if (amlStatus != null) entry.setAmlStatus(amlStatus);
        if (providerUpdatedAt != null) entry.setProviderUpdatedAt(providerUpdatedAt);
        entry.setUpdatedAt(now);
        repository.save(entry);
        return true;
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
