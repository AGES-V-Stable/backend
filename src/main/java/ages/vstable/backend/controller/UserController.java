package ages.vstable.backend.controller;

import ages.vstable.backend.dto.user.CurrentUserResponse;
import ages.vstable.backend.entity.UserEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/users")
@Tag(name = "Users")
public class UserController {

    @GetMapping("/me")
    @Operation(summary = "Returns the currently authenticated user")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current user returned successfully"),
    })
    public ResponseEntity<CurrentUserResponse> me(Authentication authentication) {
        UserEntity user = (UserEntity) authentication.getPrincipal();

        CurrentUserResponse response = new CurrentUserResponse();
        response.setId(user.getId());
        response.setName(user.getFullName());
        response.setEmail(user.getEmail());
        response.setCompanyId(user.getCompanyId());

        return ResponseEntity.ok(response);
    }
}
