package ages.vstable.backend.controller;

import ages.vstable.backend.configuration.security.CompanyAccess;
import ages.vstable.backend.dto.membership.GeneratedInvitationResponse;
import ages.vstable.backend.dto.membership.InvitationCreateRequest;
import ages.vstable.backend.dto.membership.InvitationResponse;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.service.CompanyInvitationService;
import ages.vstable.backend.utils.IfMatchHeader;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/companies/{companyId}/invitations")
@RequiredArgsConstructor
@Tag(name = "Company invitations", description = "Convites de membros da empresa, compartilhados por link")
public class CompanyInvitationController {

    private final CompanyInvitationService invitationService;

    @PostMapping
    @Operation(summary = "Cria um convite e devolve o link para compartilhar (apenas o proprietário)")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Convite pendente com o link; ETag e Cache-Control: no-store"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR ou MALFORMED_PAYLOAD: nome, e-mail ou perfil inválidos, ou campo não permitido"),
            @ApiResponse(responseCode = "403", description = "OWNER_REQUIRED, COMPANY_ACCESS_DENIED ou COMPANY_ACCESS_REVOKED"),
            @ApiResponse(responseCode = "409", description = "MEMBER_ALREADY_EXISTS, INVITATION_ALREADY_EXISTS (com details.invitationId), USER_IN_OTHER_COMPANY ou EMAIL_RESERVED"),
    })
    public ResponseEntity<GeneratedInvitationResponse> create(
            @PathVariable UUID companyId,
            @Valid @RequestBody InvitationCreateRequest request,
            @AuthenticationPrincipal UserEntity currentUser) {
        CompanyAccess.requireOwner(currentUser, companyId);
        GeneratedInvitationResponse generated = invitationService.create(companyId, currentUser, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(String.valueOf(generated.invitation().version()))
                .cacheControl(CacheControl.noStore())
                .body(generated);
    }

    @GetMapping("/{invitationId}")
    @Operation(summary = "Consulta os metadados do convite, sem segredos (apenas o proprietário)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Convite em qualquer estado; ETag com a versão do convite"),
            @ApiResponse(responseCode = "403", description = "OWNER_REQUIRED, COMPANY_ACCESS_DENIED ou COMPANY_ACCESS_REVOKED"),
            @ApiResponse(responseCode = "404", description = "INVITATION_NOT_FOUND: inexistente ou de outra empresa"),
    })
    public ResponseEntity<InvitationResponse> find(
            @PathVariable UUID companyId,
            @PathVariable UUID invitationId,
            @AuthenticationPrincipal UserEntity currentUser) {
        CompanyAccess.requireOwner(currentUser, companyId);
        InvitationResponse invitation = invitationService.find(companyId, invitationId);
        return ResponseEntity.ok().eTag(String.valueOf(invitation.version())).body(invitation);
    }

    @GetMapping("/{invitationId}/link")
    @Operation(summary = "Consulta o link vigente de um convite pendente para copiar de novo (apenas o proprietário)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Convite com o link; não gera token nem altera a versão. ETag e Cache-Control: no-store"),
            @ApiResponse(responseCode = "403", description = "OWNER_REQUIRED, COMPANY_ACCESS_DENIED ou COMPANY_ACCESS_REVOKED"),
            @ApiResponse(responseCode = "404", description = "INVITATION_NOT_FOUND: inexistente ou de outra empresa"),
            @ApiResponse(responseCode = "409", description = "INVITATION_ALREADY_USED: convite já aceito"),
            @ApiResponse(responseCode = "410", description = "INVITATION_REVOKED: convite revogado"),
    })
    public ResponseEntity<GeneratedInvitationResponse> findLink(
            @PathVariable UUID companyId,
            @PathVariable UUID invitationId,
            @AuthenticationPrincipal UserEntity currentUser) {
        CompanyAccess.requireOwner(currentUser, companyId);
        GeneratedInvitationResponse generated = invitationService.findLink(companyId, invitationId);
        return ResponseEntity.ok()
                .eTag(String.valueOf(generated.invitation().version()))
                .cacheControl(CacheControl.noStore())
                .body(generated);
    }

    @PostMapping("/{invitationId}/regenerate")
    @Operation(summary = "Gera um novo link e invalida o anterior (apenas o proprietário)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Convite com o novo link; ETag com a nova versão e Cache-Control: no-store"),
            @ApiResponse(responseCode = "403", description = "OWNER_REQUIRED, COMPANY_ACCESS_DENIED ou COMPANY_ACCESS_REVOKED"),
            @ApiResponse(responseCode = "404", description = "INVITATION_NOT_FOUND: inexistente ou de outra empresa"),
            @ApiResponse(responseCode = "409", description = "INVITATION_NOT_REGENERABLE: convite aceito ou revogado"),
            @ApiResponse(responseCode = "412", description = "RESOURCE_VERSION_MISMATCH: o convite mudou desde a versão informada"),
            @ApiResponse(responseCode = "428", description = "VERSION_REQUIRED: falta o cabeçalho If-Match"),
            @ApiResponse(responseCode = "429", description = "INVITATION_RATE_LIMITED: 60 segundos entre gerações e cinco por hora; veja Retry-After"),
    })
    public ResponseEntity<GeneratedInvitationResponse> regenerate(
            @PathVariable UUID companyId,
            @PathVariable UUID invitationId,
            @Parameter(description = "Versão do convite, entre aspas, como no ETag") @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @AuthenticationPrincipal UserEntity currentUser) {
        CompanyAccess.requireOwner(currentUser, companyId);
        long expectedVersion = IfMatchHeader.requireVersion(ifMatch);
        GeneratedInvitationResponse generated = invitationService.regenerate(companyId, invitationId, expectedVersion);
        return ResponseEntity.ok()
                .eTag(String.valueOf(generated.invitation().version()))
                .cacheControl(CacheControl.noStore())
                .body(generated);
    }

    @DeleteMapping("/{invitationId}")
    @Operation(summary = "Revoga um convite pendente e invalida o link (apenas o proprietário)")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Convite revogado; repetir uma revogação já feita também responde 204"),
            @ApiResponse(responseCode = "403", description = "OWNER_REQUIRED, COMPANY_ACCESS_DENIED ou COMPANY_ACCESS_REVOKED"),
            @ApiResponse(responseCode = "404", description = "INVITATION_NOT_FOUND: inexistente ou de outra empresa"),
            @ApiResponse(responseCode = "409", description = "INVITATION_ALREADY_USED: convite já aceito"),
            @ApiResponse(responseCode = "412", description = "RESOURCE_VERSION_MISMATCH: o convite mudou desde a versão informada"),
            @ApiResponse(responseCode = "428", description = "VERSION_REQUIRED: falta o cabeçalho If-Match"),
    })
    public ResponseEntity<Void> revoke(
            @PathVariable UUID companyId,
            @PathVariable UUID invitationId,
            @Parameter(description = "Versão do convite, entre aspas, como no ETag") @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @AuthenticationPrincipal UserEntity currentUser) {
        CompanyAccess.requireOwner(currentUser, companyId);
        long expectedVersion = IfMatchHeader.requireVersion(ifMatch);
        invitationService.revoke(companyId, invitationId, expectedVersion);
        return ResponseEntity.noContent().build();
    }
}
