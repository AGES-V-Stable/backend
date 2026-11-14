package ages.vstable.backend.controller;

import ages.vstable.backend.dto.membership.InvitationAcceptRequest;
import ages.vstable.backend.dto.membership.InvitationNewUserAcceptRequest;
import ages.vstable.backend.dto.membership.InvitationNewUserAcceptResponse;
import ages.vstable.backend.dto.membership.InvitationResolveResponse;
import ages.vstable.backend.dto.membership.InvitationTokenRequest;
import ages.vstable.backend.dto.membership.MemberResponse;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.exception.ForbiddenException;
import ages.vstable.backend.service.CompanyInvitationAcceptanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consulta e aceite do convite pelo token do link, sempre no corpo e nunca na URL. A consulta e o cadastro são
 * públicos; o aceite por quem já tem conta exige a sessão completa (login e 2FA, quando exigido). As três rotas
 * compartilham o limite de tentativas por IP (InvitationRateLimitFilter).
 */
@RestController
@RequestMapping("/v1/company-invitations")
@RequiredArgsConstructor
@Tag(name = "Company invitation acceptance", description = "Consulta e aceite do convite de membro pelo link")
public class CompanyInvitationAcceptanceController {

    private final CompanyInvitationAcceptanceService acceptanceService;

    @PostMapping("/resolve")
    @Operation(summary = "Consulta o convite pelo token, sem consumi-lo (público)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Convite pendente com empresa, perfil, modo de aceite (REGISTER ou LOGIN) e termos; Cache-Control: no-store"),
            @ApiResponse(responseCode = "400", description = "MALFORMED_PAYLOAD: corpo inválido ou campo não permitido"),
            @ApiResponse(responseCode = "404", description = "INVITATION_INVALID: token malformado ou inexistente"),
            @ApiResponse(responseCode = "409", description = "INVITATION_ALREADY_USED: convite já aceito"),
            @ApiResponse(responseCode = "410", description = "INVITATION_SUPERSEDED ou INVITATION_REVOKED"),
            @ApiResponse(responseCode = "429", description = "RATE_LIMITED: limite de tentativas por IP; veja Retry-After"),
    })
    public ResponseEntity<InvitationResolveResponse> resolve(@Valid @RequestBody InvitationTokenRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(acceptanceService.resolve(request.token()));
    }

    @PostMapping("/accept-new-user")
    @Operation(summary = "Cria a conta do convidado, o vínculo e consome o convite; não emite JWT (público)")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Conta e vínculo criados; nextAction LOGIN. Cache-Control: no-store"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR ou MALFORMED_PAYLOAD: campo ausente ou não permitido"),
            @ApiResponse(responseCode = "404", description = "INVITATION_INVALID"),
            @ApiResponse(responseCode = "409", description = "INVITATION_ALREADY_USED, USER_ALREADY_EXISTS ou TERMS_VERSION_MISMATCH"),
            @ApiResponse(responseCode = "410", description = "INVITATION_SUPERSEDED ou INVITATION_REVOKED"),
            @ApiResponse(responseCode = "422", description = "PASSWORD_CONFIRMATION_MISMATCH, PASSWORD_TOO_WEAK ou TERMS_NOT_ACCEPTED"),
            @ApiResponse(responseCode = "429", description = "RATE_LIMITED: limite de tentativas por IP; veja Retry-After"),
    })
    public ResponseEntity<InvitationNewUserAcceptResponse> acceptNewUser(
            @Valid @RequestBody InvitationNewUserAcceptRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(acceptanceService.acceptNewUser(request));
    }

    @PostMapping("/accept")
    @Operation(summary = "Aceita o convite com a conta autenticada, cujo e-mail precisa ser o do convite")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Vínculo ativo, inclusive ao repetir o mesmo aceite; ETag com a versão do vínculo e Cache-Control: no-store"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR ou MALFORMED_PAYLOAD: campo ausente ou não permitido"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED, TOKEN_INVALID ou TOKEN_EXPIRED: sessão ausente ou inválida"),
            @ApiResponse(responseCode = "403", description = "INVITATION_IDENTITY_MISMATCH: a conta autenticada não é a do convite (inclui administradores)"),
            @ApiResponse(responseCode = "404", description = "INVITATION_INVALID"),
            @ApiResponse(responseCode = "409", description = "INVITATION_ALREADY_USED, USER_IN_OTHER_COMPANY, MEMBER_ALREADY_EXISTS ou TERMS_VERSION_MISMATCH"),
            @ApiResponse(responseCode = "410", description = "INVITATION_SUPERSEDED ou INVITATION_REVOKED"),
            @ApiResponse(responseCode = "422", description = "TERMS_NOT_ACCEPTED"),
            @ApiResponse(responseCode = "429", description = "RATE_LIMITED: limite de tentativas por IP; veja Retry-After"),
    })
    public ResponseEntity<MemberResponse> accept(
            @Valid @RequestBody InvitationAcceptRequest request,
            @AuthenticationPrincipal UserEntity currentUser) {
        // Um administrador autentica, mas não é identidade de empresa e nunca é o destinatário do convite.
        if (currentUser == null) {
            throw new ForbiddenException("INVITATION_IDENTITY_MISMATCH",
                    "Este convite só pode ser aceito pela conta do e-mail convidado.");
        }
        MemberResponse member = acceptanceService.accept(currentUser, request);
        return ResponseEntity.ok()
                .eTag(String.valueOf(member.version()))
                .cacheControl(CacheControl.noStore())
                .body(member);
    }
}
