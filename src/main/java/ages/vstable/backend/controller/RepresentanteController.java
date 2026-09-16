package ages.vstable.backend.controller;

import ages.vstable.backend.dto.user.UserResponse;
import ages.vstable.backend.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/representantes")
@RequiredArgsConstructor
@Tag(name = "Representantes - Painel administrativo")
public class RepresentanteController {

    private final UserService userService;

    @GetMapping
    @Operation(summary = "Lista todos os representantes cadastrados (uso do painel administrativo)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lista de representantes retornada com sucesso"),
    })
    public ResponseEntity<List<UserResponse>> findAll() {
        return ResponseEntity.ok(userService.findAll());
    }
}
