package ages.vstable.backend.controller;

import ages.vstable.backend.configuration.security.CompanyAccess;
import ages.vstable.backend.dto.beneficiary.BeneficiaryCreateRequest;
import ages.vstable.backend.dto.beneficiary.BeneficiaryResponse;
import ages.vstable.backend.service.BeneficiaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController

@RequiredArgsConstructor
@Tag(name = "Beneficiaries", description = "Endpoints para gestão de beneficiários")
public class BeneficiaryController {

        private final BeneficiaryService beneficiaryService;

        @PostMapping("/v1/companies/{companyId}/beneficiaries")
        @Operation(summary = "Registers a new beneficiary for the authenticated user's company")
        @ApiResponses({
                        @ApiResponse(responseCode = "201", description = "Beneficiary registered successfully"),
                        @ApiResponse(responseCode = "400", description = "Missing or invalid field"),
                        @ApiResponse(responseCode = "403", description = "Company is not verified or belongs to another account"),
                        @ApiResponse(responseCode = "404", description = "Company not found"),
        })
        public ResponseEntity<BeneficiaryResponse> create(
                        @PathVariable UUID companyId,
                        @Valid @RequestBody BeneficiaryCreateRequest request,
                        Authentication authentication) {

                CompanyAccess.assertMember(authentication, companyId);

                return ResponseEntity
                                .status(HttpStatus.CREATED)
                                .body(beneficiaryService.create(companyId, request));
        }

        @GetMapping("/v1/companies/{companyId}/beneficiaries")
        @Operation(summary = "Lista os beneficiários de uma empresa (membros da empresa ou Administradores)")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "Lista paginada de beneficiários da empresa"),
                        @ApiResponse(responseCode = "401", description = "Não autenticado"),
                        @ApiResponse(responseCode = "403", description = "Empresa pertence a outra conta")
        })
        public ResponseEntity<Page<BeneficiaryResponse>> findByCompany(
                        @PathVariable UUID companyId,
                        @Parameter(description = "Busca parcial pelo apelido (nickname)") @RequestParam(required = false) String search,
                        @Parameter(description = "Busca exata por documento do beneficiário") @RequestParam(required = false) String document,
                        @Parameter(description = "Busca exata pelo país de destino") @RequestParam(required = false) String country,
                        @Parameter(description = "Parâmetros de ordenação e paginação (size, page, sort)") Pageable pageable,
                        Authentication authentication) {

                CompanyAccess.assertCanRead(authentication, companyId);

                return ResponseEntity.ok(
                                beneficiaryService.findBeneficiaries(companyId, search, document, country, pageable));
        }

        @GetMapping("/v1/companies/{companyId}/beneficiaries/{id}")
        @Operation(summary = "Consulta um beneficiário de uma empresa (membros da empresa ou Administradores)")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "Detalhes do beneficiário"),
                        @ApiResponse(responseCode = "401", description = "Não autenticado"),
                        @ApiResponse(responseCode = "403", description = "Empresa pertence a outra conta"),
                        @ApiResponse(responseCode = "404", description = "Beneficiário não encontrado nesta empresa")
        })
        public ResponseEntity<BeneficiaryResponse> findByCompanyAndId(
                        @PathVariable UUID companyId,
                        @PathVariable UUID id,
                        Authentication authentication) {

                CompanyAccess.assertCanRead(authentication, companyId);

                return ResponseEntity.ok(beneficiaryService.findByIdForCompany(companyId, id));
        }

        @GetMapping("/v1/beneficiaries")
        @PreAuthorize("hasRole('ADMIN')")
        @Operation(summary = "Lista os beneficiários de forma paginada com suporte a filtros (Apenas Administradores)")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "Lista paginada de beneficiários retornada com sucesso"),
                        @ApiResponse(responseCode = "400", description = "Parâmetros de pesquisa ou paginação inválidos"),
                        @ApiResponse(responseCode = "401", description = "Não autenticado"),
                        @ApiResponse(responseCode = "403", description = "Acesso negado (requer perfil ADMIN)")
        })
        public ResponseEntity<Page<BeneficiaryResponse>> findAll(
                        @Parameter(description = "ID da empresa associada") @RequestParam(required = false) UUID companyId,
                        @Parameter(description = "Busca parcial pelo apelido (nickname)") @RequestParam(required = false) String search,
                        @Parameter(description = "Busca exata por documento (CNPJ/CPF)") @RequestParam(required = false) String document,
                        @Parameter(description = "Busca exata pelo país de destino") @RequestParam(required = false) String country,
                        @Parameter(description = "Parâmetros de ordenação e paginação (size, page, sort)") Pageable pageable) {

                return ResponseEntity.ok(
                                beneficiaryService.findBeneficiaries(companyId, search, document, country, pageable));
        }

        @GetMapping("/v1/beneficiaries/{id}")
        @PreAuthorize("hasRole('ADMIN')")
        @Operation(summary = "Consulta os detalhes de um beneficiário específico por ID (Apenas Administradores)")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "Detalhes do beneficiário devolvidos com sucesso"),
                        @ApiResponse(responseCode = "400", description = "Formato de ID inválido"),
                        @ApiResponse(responseCode = "401", description = "Não autenticado"),
                        @ApiResponse(responseCode = "403", description = "Acesso negado (requer perfil ADMIN)"),
                        @ApiResponse(responseCode = "404", description = "Beneficiário não encontrado no sistema")
        })
        public ResponseEntity<BeneficiaryResponse> findById(
                        @Parameter(description = "UUID único do beneficiário") @PathVariable UUID id) {

                return ResponseEntity.ok(beneficiaryService.findById(id));
        }
}
