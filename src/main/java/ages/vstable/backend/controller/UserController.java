package ages.vstable.backend.controller;

import ages.vstable.backend.dto.user.CurrentUserResponse;
import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.utils.JwtTokenUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/users")
@Tag(name = "Users")
public class UserController {

    @GetMapping("/me")
    @Operation(summary = "Returns the currently authenticated company user or administrator")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current user returned successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
    })
    public ResponseEntity<CurrentUserResponse> me(Authentication authentication) {
        if (authentication == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        CurrentUserResponse response = new CurrentUserResponse();
        response.setRoles(authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList());

        if (authentication.getPrincipal() instanceof AdministratorEntity admin) {
            response.setId(admin.getId());
            response.setName(admin.getFullName());
            response.setEmail(admin.getEmail());
            response.setAccountType(JwtTokenUtils.ACCOUNT_TYPE_ADMIN);
        } else if (authentication.getPrincipal() instanceof UserEntity user) {
            response.setId(user.getId());
            response.setName(user.getFullName());
            response.setEmail(user.getEmail());
            response.setCompanyId(user.getCompanyId());
            response.setAccountType(JwtTokenUtils.ACCOUNT_TYPE_USER);
        } else {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        return ResponseEntity.ok(response);
    }
}
