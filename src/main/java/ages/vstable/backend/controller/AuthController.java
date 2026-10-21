package ages.vstable.backend.controller;

import ages.vstable.backend.dto.authentication.AuthRequestDTO;
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
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import ages.vstable.backend.dto.authentication.LoginResponseDTO;
import ages.vstable.backend.dto.authentication.LoginVerify2faRequestDTO;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import ages.vstable.backend.dto.authentication.TwoFactorActivateRequestDTO;
import ages.vstable.backend.dto.authentication.TwoFactorSetupResponseDTO;
import ages.vstable.backend.service.TwoFactorAuthService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication")
public class AuthController {

  private final AuthenticationManager authenticationManager;
  private final JwtTokenUtils jwtTokenUtil;
  private final TwoFactorAuthService twoFactorAuthService;

  @PostMapping(path = "login")
  @Operation(summary = "Authenticates email/password. Returns a 2FA challenge if 2FA is enabled/required, or a JWT token otherwise")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Authentication or 2FA challenge successful"),
      @ApiResponse(responseCode = "401", description = "User not found or invalid credentials"),
      @ApiResponse(responseCode = "423", description = "User is locked"),
  })
  public ResponseEntity<?> getPermissions(@RequestBody AuthRequestDTO request) {
    try {
      Authentication authenticate = authenticationManager.authenticate(
          new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword()));

      UserDetails principal = (UserDetails) authenticate.getPrincipal();

      if (twoFactorAuthService.requiresTwoFactorOnLogin(principal)){
        LoginResponseDTO challengeResponse = twoFactorAuthService.createLoginChallenge(principal);
        return ResponseEntity.ok(challengeResponse);
      }

      String token = jwtTokenUtil.generateToken(principal);
      return ResponseEntity
          .ok()
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
          .build();
    } catch (LockedException | DisabledException le) {
      log.error(le.getMessage());
      return ResponseEntity.status(HttpStatus.LOCKED)
          .body(Map.of("code", "ACCOUNT_LOCKED", "message", le.getMessage()));
    } catch (AuthenticationException ex) {
      log.error(ex.getMessage());
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(Map.of("code", "INVALID_CREDENTIALS", "message", "Invalid credentials"));
    }
  }

  @PostMapping(path = "login/verify-2fa")
  @Operation(summary = "Validates the temporary challengeToken and 6-digit TOTP code to issue the session JWT")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "2FA verified; session JWT issued"),
      @ApiResponse(responseCode = "400", description = "Invalid payload format"),
      @ApiResponse(responseCode = "401", description = "Invalid/expired challenge token or invalid TOTP code"),
      @ApiResponse(responseCode = "423", description = "Account locked due to too many failed attempts"),
  })
  public ResponseEntity<?> verifyLogin2fa(@Valid @RequestBody LoginVerify2faRequestDTO request) {
    try {
      UserDetails principal = twoFactorAuthService.verifyLoginChallenge(
          request.getChallengeToken(), request.getCode());
      String jwt = jwtTokenUtil.generateToken(principal);
      return ResponseEntity.ok()
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
          .body(LoginResponseDTO.builder().token(jwt).build());
    } catch (LockedException | DisabledException le) {
      return ResponseEntity.status(HttpStatus.LOCKED)
          .body(Map.of("code", "ACCOUNT_LOCKED", "message", le.getMessage()));
    } catch (AuthenticationException ex) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(Map.of("code", "INVALID_2FA_CODE", "message", ex.getMessage()));
    }
  }

  @PostMapping(path = "2fa/setup")
  @Operation(summary = "Generates a new temporary TOTP secret for the authenticated account and returns the otpauth:// URI")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "2FA setup URI generated"),
      @ApiResponse(responseCode = "401", description = "Not authenticated"),
  })
  public ResponseEntity<TwoFactorSetupResponseDTO> setupTwoFactor(
      @AuthenticationPrincipal UserDetails principal) {
    if (principal == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    return ResponseEntity.ok(twoFactorAuthService.setupTwoFactor(principal));
  }

  @PostMapping(path = "2fa/activate")
  @Operation(summary = "Validates the 6-digit TOTP code and activates 2FA on the authenticated account")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "2FA activated successfully"),
      @ApiResponse(responseCode = "400", description = "Invalid request format"),
      @ApiResponse(responseCode = "401", description = "Not authenticated or invalid TOTP code"),
      @ApiResponse(responseCode = "422", description = "2FA setup not initiated"),
  })
  public ResponseEntity<Map<String, Object>> activateTwoFactor(
      @AuthenticationPrincipal UserDetails principal,
      @Valid @RequestBody TwoFactorActivateRequestDTO request) {
    if (principal == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(Map.of("code", "UNAUTHENTICATED", "message", "Authentication required"));
    }
    try {
      twoFactorAuthService.activateTwoFactor(principal, request.getCode());
      return ResponseEntity.ok(Map.of("twoFactorEnabled", true, "message", "2FA activated successfully"));
    } catch (AuthenticationException ex) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(Map.of("code", "INVALID_2FA_CODE", "message", "Invalid 2FA verification code"));
    }
  }
}
