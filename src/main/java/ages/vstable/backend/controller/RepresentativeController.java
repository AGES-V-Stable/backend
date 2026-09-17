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
@RequestMapping("/v1/representatives")
@RequiredArgsConstructor
@Tag(name = "Representatives - Admin panel")
public class RepresentativeController {

    private final UserService userService;

    @GetMapping
    @Operation(summary = "Lists all registered representatives (used by the admin panel)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of representatives returned successfully"),
    })
    public ResponseEntity<List<UserResponse>> findAll() {
        return ResponseEntity.ok(userService.findAll());
    }
}
