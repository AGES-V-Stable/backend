package ages.vstable.backend.controller;

import ages.vstable.backend.dto.authentication.AuthRequestDTO;
import ages.vstable.backend.entity.UsuarioEntity;
import ages.vstable.backend.repository.UsuarioRepository;
import ages.vstable.backend.utils.JwtTokenUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
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
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequiredArgsConstructor
public class AuthController {

  private final AuthenticationManager authenticationManager;
  private final JwtTokenUtils jwtTokenUtil;
  private final UsuarioRepository userRepository;

  @PostMapping(path = "login")
  public ResponseEntity<?> getPermissions(@RequestBody AuthRequestDTO request) {
    try {
      UsuarioEntity user = userRepository
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
          .header(HttpHeaders.AUTHORIZATION, jwtTokenUtil.generateToken(user))
          .build();
    } catch (LockedException le) {
      log.error(le.getMessage());
      return ResponseEntity.status(HttpStatus.LOCKED).body(le.getMessage());
    } catch (BadCredentialsException ex) {
      log.error(ex.getMessage());
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
  }
}

