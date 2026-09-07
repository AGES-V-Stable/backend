package ages.vstable.backend.service;

import ages.vstable.backend.exception.DocumentoInvalidoException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;
import java.util.Map;

@Component
public class DocumentUploadPolicy {

    private static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;

    private static final Map<String, String> ALLOWED_CONTENT_TYPES = Map.of(
            "application/pdf", ".pdf",
            "image/png", ".png",
            "image/jpeg", ".jpg"
    );

    public ValidatedDocument validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new DocumentoInvalidoException("O arquivo é obrigatório");
        }

        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new DocumentoInvalidoException("O arquivo deve ter no máximo 10 MB");
        }

        String contentType = normalizeContentType(file.getContentType());
        String extension = ALLOWED_CONTENT_TYPES.get(contentType);

        if (extension == null) {
            throw new DocumentoInvalidoException(
                    "Formato não permitido. Envie um arquivo PDF, PNG ou JPEG"
            );
        }

        return new ValidatedDocument(
                normalizeFileName(file.getOriginalFilename()),
                contentType,
                extension
        );
    }

    private String normalizeContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return "";
        }

        return contentType.toLowerCase(Locale.ROOT);
    }

    private String normalizeFileName(String originalFileName) {
        String cleanPath = StringUtils.cleanPath(
                originalFileName == null ? "arquivo" : originalFileName
        );
        String fileName = StringUtils.getFilename(cleanPath);

        if (!StringUtils.hasText(fileName) || ".".equals(fileName) || "..".equals(fileName)) {
            fileName = "arquivo";
        }

        fileName = fileName.replaceAll("[\\p{Cntrl}]", "_");

        return fileName.length() <= 255
                ? fileName
                : fileName.substring(0, 255);
    }
}
