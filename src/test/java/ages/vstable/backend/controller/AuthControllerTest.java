package ages.vstable.backend.controller;

import ages.vstable.backend.dto.authentication.AuthRequestDTO;
import ages.vstable.backend.dto.authentication.LoginResponseDTO;
import ages.vstable.backend.dto.authentication.LoginVerify2faRequestDTO;
import ages.vstable.backend.dto.authentication.TwoFactorActivateRequestDTO;
import ages.vstable.backend.dto.authentication.TwoFactorSetupResponseDTO;
import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.service.TwoFactorAuthService;
import ages.vstable.backend.utils.JwtTokenUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private JwtTokenUtils jwtTokenUtil;

    @Mock
    private TwoFactorAuthService twoFactorAuthService;

    @InjectMocks
    private AuthController authController;

    private AuthRequestDTO request;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        request = new AuthRequestDTO();
        request.setEmail("user@email.com");
        request.setPassword("password");

        user = new UserEntity();
        user.setEmail("user@email.com");
    }

    @Test
    void shouldLoginSuccessfullyWithout2faWhenNotEnabled() {
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        when(twoFactorAuthService.requiresTwoFactorOnLogin(user)).thenReturn(false);
        when(jwtTokenUtil.generateToken(user)).thenReturn("jwt-token");

        ResponseEntity<LoginResponseDTO> response = authController.getPermissions(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Bearer jwt-token", response.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        verify(jwtTokenUtil).generateToken(user);
    }

    @Test
    void shouldReturnChallengeWithoutSessionJwtWhenAccountRequires2fa() {
        AdministratorEntity admin = AdministratorEntity.builder()
                .email("admin@vstable.com")
                .require2fa(true)
                .build();
        LoginResponseDTO challengeDto = LoginResponseDTO.builder()
                .requires2fa(true)
                .challengeToken("temp-challenge-token")
                .otpauthUri("otpauth://totp/V-Stable:admin%40vstable.com?secret=ABC")
                .build();

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
        when(twoFactorAuthService.requiresTwoFactorOnLogin(admin)).thenReturn(true);
        when(twoFactorAuthService.createLoginChallenge(admin)).thenReturn(challengeDto);

        ResponseEntity<LoginResponseDTO> response = authController.getPermissions(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNull(response.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        assertEquals(challengeDto, response.getBody());
        verifyNoInteractions(jwtTokenUtil);
    }

    @Test
    void shouldVerify2faChallengeAndIssueFinalJwt() {
        LoginVerify2faRequestDTO verifyRequest = new LoginVerify2faRequestDTO("temp-challenge", "123456");
        when(twoFactorAuthService.verifyLoginChallenge("temp-challenge", "123456")).thenReturn(user);
        when(jwtTokenUtil.generateToken(user)).thenReturn("final-session-jwt");

        ResponseEntity<LoginResponseDTO> response = authController.verifyLogin2fa(verifyRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Bearer final-session-jwt", response.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        LoginResponseDTO body = response.getBody();
        assertNotNull(body);
        assertEquals("final-session-jwt", body.getToken());
    }

    @Test
    void shouldReturnUnauthorizedWhenVerify2faFails() {
        LoginVerify2faRequestDTO verifyRequest = new LoginVerify2faRequestDTO("temp-challenge", "000000");
        when(twoFactorAuthService.verifyLoginChallenge("temp-challenge", "000000"))
                .thenThrow(new BadCredentialsException("Invalid 2FA verification code"));

        ResponseEntity<LoginResponseDTO> response = authController.verifyLogin2fa(verifyRequest);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("INVALID_2FA_CODE", response.getBody().getCode());
        verifyNoInteractions(jwtTokenUtil);
    }

    @Test
    void shouldReturnLockedWhenVerify2faIsLocked() {
        LoginVerify2faRequestDTO verifyRequest = new LoginVerify2faRequestDTO("temp-challenge", "000000");
        when(twoFactorAuthService.verifyLoginChallenge("temp-challenge", "000000"))
                .thenThrow(new LockedException("Too many failed 2FA attempts"));

        ResponseEntity<LoginResponseDTO> response = authController.verifyLogin2fa(verifyRequest);

        assertEquals(HttpStatus.LOCKED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("ACCOUNT_LOCKED", response.getBody().getCode());
        verifyNoInteractions(jwtTokenUtil);
    }

    @Test
    void shouldReturnUnauthorizedWhenUserDoesNotExist() {
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new UsernameNotFoundException("User not found"));

        ResponseEntity<LoginResponseDTO> response = authController.getPermissions(request);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("INVALID_CREDENTIALS", response.getBody().getCode());
        assertEquals("Invalid credentials", response.getBody().getMessage());
        verifyNoInteractions(jwtTokenUtil);
    }

    @Test
    void shouldReturnUnauthorizedWhenCredentialsAreInvalid() {
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("Invalid credentials"));

        ResponseEntity<LoginResponseDTO> response = authController.getPermissions(request);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("INVALID_CREDENTIALS", response.getBody().getCode());
        assertEquals("Invalid credentials", response.getBody().getMessage());
        verifyNoInteractions(jwtTokenUtil);
    }

    @Test
    void shouldReturnLockedWhenUserIsLocked() {
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new LockedException("User is locked"));

        ResponseEntity<LoginResponseDTO> response = authController.getPermissions(request);

        assertEquals(HttpStatus.LOCKED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("ACCOUNT_LOCKED", response.getBody().getCode());
        assertEquals("User is locked", response.getBody().getMessage());
        verifyNoInteractions(jwtTokenUtil);
    }

    @Test
    void shouldAuthenticateWithProvidedCredentials() {
        when(authenticationManager.authenticate(any()))
                .thenReturn(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        when(twoFactorAuthService.requiresTwoFactorOnLogin(user)).thenReturn(false);
        when(jwtTokenUtil.generateToken(user)).thenReturn("jwt-token");

        authController.getPermissions(request);

        ArgumentCaptor<UsernamePasswordAuthenticationToken> captor =
                ArgumentCaptor.forClass(UsernamePasswordAuthenticationToken.class);
        verify(authenticationManager).authenticate(captor.capture());

        assertEquals("user@email.com", captor.getValue().getPrincipal());
        assertEquals("password", captor.getValue().getCredentials());
    }

    @Test
    void shouldSetupTwoFactorWhenAuthenticated() {
        TwoFactorSetupResponseDTO setupDto = TwoFactorSetupResponseDTO.builder()
                .otpauthUri("otpauth://totp/V-Stable:user%40email.com?secret=ABC")
                .twoFactorEnabled(false)
                .build();
        when(twoFactorAuthService.setupTwoFactor(user, "123456")).thenReturn(setupDto);

        ResponseEntity<TwoFactorSetupResponseDTO> response = authController.setupTwoFactor("123456", user);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(setupDto, response.getBody());
    }

    @Test
    void shouldReturnUnauthorizedOnSetupWhenNotAuthenticated() {
        ResponseEntity<TwoFactorSetupResponseDTO> response = authController.setupTwoFactor(null, null);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void shouldActivateTwoFactorSuccessfully() {
        TwoFactorActivateRequestDTO activateReq = new TwoFactorActivateRequestDTO("123456");

        ResponseEntity<Map<String, Object>> response = authController.activateTwoFactor(user, activateReq);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Boolean.TRUE, response.getBody().get("success"));
        verify(twoFactorAuthService).activateTwoFactor(user, "123456");
    }

    @Test
    void shouldReturnUnauthorizedOnActivateWhenCodeIsInvalid() {
        TwoFactorActivateRequestDTO activateReq = new TwoFactorActivateRequestDTO("000000");
        doThrow(new BadCredentialsException("Invalid code"))
                .when(twoFactorAuthService).activateTwoFactor(user, "000000");

        ResponseEntity<Map<String, Object>> response = authController.activateTwoFactor(user, activateReq);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("INVALID_2FA_CODE", response.getBody().get("code"));
    }
}
