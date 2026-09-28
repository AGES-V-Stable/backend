package ages.vstable.backend.controller;

import ages.vstable.backend.dto.authentication.AuthRequestDTO;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.repository.UserRepository;
import ages.vstable.backend.utils.JwtTokenUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication")
public class AuthController {

  private final AuthenticationManager authenticationManager;
  private final JwtTokenUtils jwtTokenUtil;
  private final UserRepository userRepository;

  @PostMapping(path = "login")
  @Operation(summary = "Authenticates a user and returns a JWT token in the Authorization header")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Authentication successful"),
      @ApiResponse(responseCode = "401", description = "User not found or invalid credentials"),
      @ApiResponse(responseCode = "423", description = "User is locked"),
  })
  public ResponseEntity<Map<String, String>> getPermissions(@RequestBody AuthRequestDTO request) {
    try {
      UserEntity user = userRepository
          .findByEmail(request.getEmail())
          .orElseThrow(() -> new BadCredentialsException("User not found"));
      UsernamePasswordAuthenticationToken userPassAuth = new UsernamePasswordAuthenticationToken(
              request.getEmail(),
              request.getPassword());
      Authentication authenticate = authenticationManager.authenticate(
          userPassAuth);

      SecurityContextHolder.getContext().setAuthentication(authenticate);

      return ResponseEntity
          .ok()
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenUtil.generateToken(user))
          .build();
    } catch (LockedException le) {
      log.error(le.getMessage());
      return ResponseEntity.status(HttpStatus.LOCKED).body(Map.of("message", le.getMessage()));
    } catch (BadCredentialsException ex) {
      log.error(ex.getMessage());
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
  }
}

