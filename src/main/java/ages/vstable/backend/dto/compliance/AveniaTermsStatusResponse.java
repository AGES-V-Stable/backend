package ages.vstable.backend.dto.compliance;

import java.util.List;

/**
 * Termos da Avenia para a subconta do titular: versão vigente, documentos a exibir e se
 * já foram aceitos. O token de aceite da Avenia nunca é exposto ao cliente.
 */
public record AveniaTermsStatusResponse(int version, boolean accepted, List<Document> documents) {

    public AveniaTermsStatusResponse {
        documents = documents == null ? List.of() : List.copyOf(documents);
    }

    public record Document(String key, String name, String url) {
    }
}
