package ages.vstable.backend.controller;

import ages.vstable.backend.configuration.security.CompanyAccess;
import ages.vstable.backend.dto.membership.MemberListResponse;
import ages.vstable.backend.dto.membership.MemberResponse;
import ages.vstable.backend.dto.membership.MemberRoleUpdateRequest;
import ages.vstable.backend.entity.CompanyMembershipEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.service.CompanyMemberService;
import ages.vstable.backend.service.MembershipResponseMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/companies/{companyId}/members")
@RequiredArgsConstructor
@Tag(name = "Company members", description = "Membros e convites pendentes da empresa")
public class CompanyMemberController {

    private final CompanyMemberService memberService;

    @GetMapping
    @Operation(summary = "Lista os membros ativos e os convites pendentes da empresa (apenas o proprietário)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Página de membros e convites, com as contagens do conjunto completo"),
            @ApiResponse(responseCode = "400", description = "INVALID_PARAMETER: page menor que 0 ou size fora de 1 a 100"),
            @ApiResponse(responseCode = "403", description = "OWNER_REQUIRED, COMPANY_ACCESS_DENIED ou COMPANY_ACCESS_REVOKED"),
    })
    public ResponseEntity<MemberListResponse> list(
            @PathVariable UUID companyId,
            @Parameter(description = "Página, base 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Itens por página, de 1 a 100") @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserEntity currentUser) {
        CompanyAccess.requireOwner(currentUser, companyId);
        return ResponseEntity.ok(memberService.list(companyId, page, size));
    }

    @PatchMapping("/{memberId}")
    @Operation(summary = "Altera o perfil OPERATOR/VIEWER de um membro ativo (apenas OWNER)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Membro atualizado; ETag com a nova versao"),
            @ApiResponse(responseCode = "403", description = "OWNER_REQUIRED ou COMPANY_ACCESS_REVOKED"),
            @ApiResponse(responseCode = "404", description = "MEMBER_NOT_FOUND"),
            @ApiResponse(responseCode = "409", description = "OWNER_PROTECTED ou MEMBERSHIP_NOT_ACTIVE"),
            @ApiResponse(responseCode = "412", description = "RESOURCE_VERSION_MISMATCH"),
            @ApiResponse(responseCode = "428", description = "VERSION_REQUIRED"),
    })
    public ResponseEntity<MemberResponse> changeRole(
            @PathVariable UUID companyId,
            @PathVariable UUID memberId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody MemberRoleUpdateRequest request,
            @AuthenticationPrincipal UserEntity currentUser) {
        CompanyAccess.requireOwner(currentUser, companyId);
        long version = ages.vstable.backend.utils.IfMatchHeader.requireVersion(ifMatch);
        MemberResponse updated = memberService.changeRole(companyId, memberId, version, request.role(),
                currentUser.getId());
        return ResponseEntity.ok().eTag(String.valueOf(updated.version())).body(updated);
    }

    @DeleteMapping("/{memberId}")
    @Operation(summary = "Revoga o acesso empresarial do membro sem excluir sua identidade")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Vinculo revogado; repeticao continua 204"),
            @ApiResponse(responseCode = "403", description = "OWNER_REQUIRED ou COMPANY_ACCESS_REVOKED"),
            @ApiResponse(responseCode = "404", description = "MEMBER_NOT_FOUND"),
            @ApiResponse(responseCode = "409", description = "OWNER_PROTECTED"),
            @ApiResponse(responseCode = "412", description = "RESOURCE_VERSION_MISMATCH"),
            @ApiResponse(responseCode = "428", description = "VERSION_REQUIRED"),
    })
    public ResponseEntity<Void> remove(
            @PathVariable UUID companyId,
            @PathVariable UUID memberId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @AuthenticationPrincipal UserEntity currentUser) {
        CompanyAccess.requireOwner(currentUser, companyId);
        long version = ages.vstable.backend.utils.IfMatchHeader.requireVersion(ifMatch);
        memberService.revoke(companyId, memberId, version, currentUser.getId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    @Operation(summary = "Consulta o próprio vínculo com a empresa, ativo ou removido")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Vínculo do usuário autenticado; ETag com a versão do vínculo"),
            @ApiResponse(responseCode = "403", description = "COMPANY_ACCESS_DENIED: o usuário não tem vínculo nesta empresa"),
    })
    public ResponseEntity<MemberResponse> me(
            @PathVariable UUID companyId,
            @AuthenticationPrincipal UserEntity currentUser) {
        CompanyMembershipEntity membership = CompanyAccess.requireSelfMembership(currentUser, companyId);
        MemberResponse member = MembershipResponseMapper.member(membership, currentUser);
        return ResponseEntity.ok().eTag(String.valueOf(member.version())).body(member);
    }
}
