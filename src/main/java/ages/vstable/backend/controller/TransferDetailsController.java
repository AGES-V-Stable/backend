package ages.vstable.backend.controller;

import ages.vstable.backend.configuration.security.CompanyAccess;
import ages.vstable.backend.dto.transaction.TransferDetailsResponse;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.exception.TransferDetailsExceptionHandler.ErrorBody;
import ages.vstable.backend.service.TransferDetailsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Detalhes e comprovante de uma transferência da empresa do usuário autenticado. A empresa vem
 * sempre do usuário; não existe parâmetro para escolhê-la.
 */
@RestController
@RequestMapping("/api/transferencias")
@RequiredArgsConstructor
@Tag(name = "Transfer details", description = "Detalhes e comprovante de transferências da empresa do usuário")
public class TransferDetailsController {

    private final TransferDetailsService transferDetailsService;

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Consulta os detalhes registrados de uma transferência da própria empresa")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Detalhes da transferência"),
            @ApiResponse(responseCode = "400", description = "INVALID_TRANSFER_ID: identificador inválido",
                    content = @Content(schema = @Schema(implementation = ErrorBody.class))),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED: token ausente, inválido ou expirado",
                    content = @Content(schema = @Schema(implementation = ErrorBody.class))),
            @ApiResponse(responseCode = "403",
                    description = "USER_NOT_VERIFIED (sem a verificação exigida) ou COMPANY_ACCESS_REQUIRED (sem vínculo empresarial)",
                    content = @Content(schema = @Schema(implementation = ErrorBody.class))),
            @ApiResponse(responseCode = "404", description = "TRANSFER_NOT_FOUND: inexistente ou de outra empresa",
                    content = @Content(schema = @Schema(implementation = ErrorBody.class))),
            @ApiResponse(responseCode = "500", description = "INTERNAL_ERROR",
                    content = @Content(schema = @Schema(implementation = ErrorBody.class)))
    })
    public ResponseEntity<TransferDetailsResponse> getDetails(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(transferDetailsService.getDetails(id, currentUser(authentication)));
    }

    @GetMapping(value = "/{id}/comprovante", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "Baixa o comprovante em PDF de uma transferência concluída da própria empresa")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Bytes do PDF (Content-Disposition: attachment)",
                    content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE)),
            @ApiResponse(responseCode = "400", description = "INVALID_TRANSFER_ID: identificador inválido",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorBody.class))),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED: token ausente, inválido ou expirado",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorBody.class))),
            @ApiResponse(responseCode = "403",
                    description = "USER_NOT_VERIFIED (sem a verificação exigida) ou COMPANY_ACCESS_REQUIRED (sem vínculo empresarial)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorBody.class))),
            @ApiResponse(responseCode = "404", description = "TRANSFER_NOT_FOUND: inexistente ou de outra empresa",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorBody.class))),
            @ApiResponse(responseCode = "409", description = "RECEIPT_UNAVAILABLE: a transferência ainda não tem comprovante",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorBody.class))),
            @ApiResponse(responseCode = "500", description = "INTERNAL_ERROR (inclui falha ao gerar o PDF)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorBody.class)))
    })
    public ResponseEntity<byte[]> downloadReceipt(@PathVariable UUID id, Authentication authentication) {
        TransferDetailsService.Receipt receipt = transferDetailsService.getReceipt(id, currentUser(authentication));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(receipt.filename()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(receipt.content());
    }

    /** Administrador (ou principal sem empresa) vira nulo e é recusado pelo serviço. */
    private static UserEntity currentUser(Authentication authentication) {
        return CompanyAccess.companyUser(authentication).orElse(null);
    }
}
