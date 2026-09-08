package ages.vstable.backend.service;

import ages.vstable.backend.exception.DocumentoInvalidoException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentUploadPolicyTest {

    private static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;

    private final DocumentUploadPolicy policy = new DocumentUploadPolicy();

    @Test
    void validate_shouldThrowDocumentoInvalido_whenFileIsNull() {
        assertThatThrownBy(() -> policy.validate(null))
                .isInstanceOf(DocumentoInvalidoException.class)
                .hasMessage("O arquivo é obrigatório");
    }

    @Test
    void validate_shouldThrowDocumentoInvalido_whenFileIsEmpty() {
        MultipartFile file = pdf(new byte[0]);

        assertThatThrownBy(() -> policy.validate(file))
                .isInstanceOf(DocumentoInvalidoException.class)
                .hasMessage("O arquivo é obrigatório");
    }

    @Test
    void validate_shouldThrowDocumentoInvalido_whenFileExceedsTenMegabytes() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(MAX_FILE_SIZE_BYTES + 1);

        assertThatThrownBy(() -> policy.validate(file))
                .isInstanceOf(DocumentoInvalidoException.class)
                .hasMessage("O arquivo deve ter no máximo 10 MB");
    }

    @Test
    void validate_shouldAcceptFile_whenSizeIsExactlyTenMegabytes() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(MAX_FILE_SIZE_BYTES);
        when(file.getContentType()).thenReturn("application/pdf");
        when(file.getOriginalFilename()).thenReturn("balanco.pdf");

        ValidatedDocument document = policy.validate(file);

        assertThat(document.extension()).isEqualTo(".pdf");
        assertThat(document.fileName()).isEqualTo("balanco.pdf");
    }

    @Test
    void validate_shouldThrowDocumentoInvalido_whenContentTypeIsNotAllowed() {
        MultipartFile file = new MockMultipartFile(
                "arquivo", "notas.txt", "text/plain", bytes("conteudo"));

        assertThatThrownBy(() -> policy.validate(file))
                .isInstanceOf(DocumentoInvalidoException.class)
                .hasMessage("Formato não permitido. Envie um arquivo PDF, PNG ou JPEG");
    }

    @Test
    void validate_shouldThrowDocumentoInvalido_whenContentTypeIsMissing() {
        MultipartFile file = new MockMultipartFile(
                "arquivo", "mistério", null, bytes("conteudo"));

        assertThatThrownBy(() -> policy.validate(file))
                .isInstanceOf(DocumentoInvalidoException.class)
                .hasMessage("Formato não permitido. Envie um arquivo PDF, PNG ou JPEG");
    }

    @ParameterizedTest
    @CsvSource({
            "application/pdf, .pdf",
            "image/png,       .png",
            "image/jpeg,      .jpg"
    })
    void validate_shouldReturnValidatedDocument_whenContentTypeIsAllowed(
            String contentType,
            String expectedExtension
    ) {
        MultipartFile file = new MockMultipartFile(
                "arquivo", "documento", contentType, bytes("conteudo"));

        ValidatedDocument document = policy.validate(file);

        assertThat(document.contentType()).isEqualTo(contentType);
        assertThat(document.extension()).isEqualTo(expectedExtension);
        assertThat(document.fileName()).isEqualTo("documento");
    }

    @Test
    void validate_shouldNormalizeContentTypeToLowerCase() {
        MultipartFile file = new MockMultipartFile(
                "arquivo", "contrato.PDF", "APPLICATION/PDF", bytes("conteudo"));

        ValidatedDocument document = policy.validate(file);

        assertThat(document.contentType()).isEqualTo("application/pdf");
        assertThat(document.extension()).isEqualTo(".pdf");
    }

    @Test
    void validate_shouldStripDirectories_whenFileNameContainsUnixPathTraversal() {
        ValidatedDocument document = policy.validate(pdfNamed("../../../etc/passwd"));

        assertThat(document.fileName())
                .isEqualTo("passwd")
                .doesNotContain("/", "..");
    }

    @Test
    void validate_shouldStripDirectories_whenFileNameContainsWindowsPathTraversal() {
        ValidatedDocument document =
                policy.validate(pdfNamed("..\\..\\Windows\\System32\\config\\SAM"));

        assertThat(document.fileName())
                .isEqualTo("SAM")
                .doesNotContain("\\", "..");
    }

    @Test
    void validate_shouldKeepBareFileName_whenFileNameContainsAbsolutePath() {
        ValidatedDocument document =
                policy.validate(pdfNamed("/home/user/Documentos/contrato social.pdf"));

        assertThat(document.fileName()).isEqualTo("contrato social.pdf");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", ".", ".."})
    void validate_shouldFallBackToDefaultName_whenFileNameIsBlankOrDots(String originalName) {
        ValidatedDocument document = policy.validate(pdfNamed(originalName));

        assertThat(document.fileName()).isEqualTo("arquivo");
    }

    @Test
    void validate_shouldFallBackToDefaultName_whenFileNameIsNull() {
        ValidatedDocument document = policy.validate(pdfNamed(null));

        assertThat(document.fileName()).isEqualTo("arquivo");
    }

    @Test
    void validate_shouldReplaceControlCharactersInFileName() {
        ValidatedDocument document = policy.validate(pdfNamed("nota\tfiscal\n.pdf"));

        assertThat(document.fileName()).isEqualTo("nota_fiscal_.pdf");
    }

    @Test
    void validate_shouldTruncateFileName_whenLongerThan255Characters() {
        String longName = "a".repeat(300) + ".pdf";

        ValidatedDocument document = policy.validate(pdfNamed(longName));

        assertThat(document.fileName())
                .hasSize(255)
                .isEqualTo("a".repeat(255));
    }

    private static MultipartFile pdf(byte[] content) {
        return new MockMultipartFile("arquivo", "documento.pdf", "application/pdf", content);
    }

    private static MultipartFile pdfNamed(String originalFilename) {
        return new MockMultipartFile(
                "arquivo", originalFilename, "application/pdf", bytes("conteudo"));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
