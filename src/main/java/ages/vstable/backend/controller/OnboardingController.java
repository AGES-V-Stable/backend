package ages.vstable.backend.controller;

import ages.vstable.backend.dto.onboarding.OnboardingRequestDTO;
import ages.vstable.backend.dto.onboarding.OnboardingResponseDTO;
import ages.vstable.backend.service.OnboardingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/cadastros")
@RequiredArgsConstructor
@Tag(name = "Onboarding do representante e da empresa")
public class OnboardingController {

    private final OnboardingService onboardingService;

    @PostMapping("/onboarding")
    @Operation(summary = "Cadastra o representante e a empresa de uma vez, antes do KYC facial (Avenia)")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Representante e empresa cadastrados; verificação de KYC criada como pendente"),
            @ApiResponse(responseCode = "400", description = "Campo obrigatório ausente"),
            @ApiResponse(responseCode = "409", description = "E-mail ou CNPJ já cadastrado"),
            @ApiResponse(responseCode = "422", description = "Senha e confirmação divergentes, senha fraca, e-mail/CNPJ/CEP em formato inválido"),
    })
    public ResponseEntity<OnboardingResponseDTO> create(@Valid @RequestBody OnboardingRequestDTO request) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(onboardingService.realizarOnboarding(request));
    }
}
