package ages.vstable.backend.external.avenia.dto;

import lombok.Data;

@Data
public class AveniaDocumentStatusResponse {

    private Document document;

    @Data
    public static class Document {
        private String id;
        private String documentType;
        private String uploadStatusFront;
        private String uploadErrorFront;
        private String uploadStatusBack;
        private String uploadErrorBack;
        private boolean ready;
    }
}
