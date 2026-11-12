package ages.vstable.backend.controller;

import ages.vstable.backend.configuration.security.CompanyAccess;
import ages.vstable.backend.dto.membership.MemberListResponse;
import ages.vstable.backend.dto.membership.MemberResponse;
import ages.vstable.backend.entity.CompanyMembershipEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.service.CompanyMemberService;
import ages.vstable.backend.service.MembershipResponseMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
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
