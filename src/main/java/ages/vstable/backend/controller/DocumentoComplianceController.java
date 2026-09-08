package ages.vstable.backend.controller;

import ages.vstable.backend.dto.documento.DocumentoComplianceResponse;
import ages.vstable.backend.entity.enums.TipoDocumento;
import ages.vstable.backend.service.DocumentoComplianceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/empresas/{empresaId}/documentos")
@RequiredArgsConstructor
public class DocumentoComplianceController {

    private final DocumentoComplianceService documentoService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentoComplianceResponse> upload(
            @PathVariable UUID empresaId,
            @RequestPart("arquivo") MultipartFile arquivo,
            @RequestParam TipoDocumento tipoDocumento
    ) {
        DocumentoComplianceResponse response = documentoService.upload(
                empresaId,
                tipoDocumento,
                arquivo
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
