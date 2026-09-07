package ages.vstable.backend.service;

public record ValidatedDocument(
        String fileName,
        String contentType,
        String extension
) {
}
