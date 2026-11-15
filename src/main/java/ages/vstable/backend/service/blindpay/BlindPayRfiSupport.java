package ages.vstable.backend.service.blindpay;

import ages.vstable.backend.dto.blindpay.BlindPayRfiDetailsResponse;
import ages.vstable.backend.exception.BlindPayIntegrationException;
import ages.vstable.backend.external.blindpay.BlindPayGateway;
import ages.vstable.backend.external.blindpay.dto.BlindPayRfiResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pedido de informações (RFI) da BlindPay, comum ao KYB da empresa e ao KYC individual:
 * consulta do pedido pendente, conversão para a API e validação das respostas.
 */
final class BlindPayRfiSupport {

    static final int MAX_RFI_TEXT_LENGTH = 4096;
    static final int MAX_RFI_FILES = 20;

    private static final String RFI_PENDING = "pending";
    private static final String HTTPS_PREFIX = "https://";

    private BlindPayRfiSupport() {
    }

    /** RFI pendente do cliente, ou {@code null} quando não há pedido aberto. */
    static BlindPayRfiResponse pending(BlindPayGateway gateway, String customerId) {
        BlindPayRfiResponse rfi = gateway.getCustomerRfi(customerId);
        if (rfi == null || !RFI_PENDING.equalsIgnoreCase(rfi.status())) {
            return null;
        }
        if (rfi.customerId() != null && !rfi.customerId().equals(customerId)) {
            throw BlindPayIntegrationException.invalidResponse(
                    "BlindPay returned an information request of another customer");
        }
        return rfi;
    }

    static BlindPayRfiDetailsResponse toDetails(BlindPayRfiResponse rfi) {
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
     * As chaves precisam ser campos do RFI, os obrigatórios precisam estar presentes e
     * documentos são URLs https (até 20 por campo).
     */
    static Map<String, Object> validateResponses(BlindPayRfiResponse rfi, Map<String, Object> responses) {
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
            normalized.put(entry.getKey(), value(entry.getKey(), entry.getValue(), field));
        }
        for (BlindPayRfiResponse.Field field : fields.values()) {
            if (Boolean.TRUE.equals(field.required()) && !normalized.containsKey(field.key())) {
                throw new IllegalArgumentException("responses." + field.key() + ": must be provided");
            }
        }
        return normalized;
    }

    private static Object value(String key, Object value, BlindPayRfiResponse.Field field) {
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
}
