package ages.vstable.backend.utils;

import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.UserEntity;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.core.userdetails.UserDetails;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@org.junit.jupiter.api.Disabled
class JwtTokenUtilsTest {

    private static final String SECRET =
            "my-test-secret-key-that-is-at-least-64-bytes-long-for-hs512-signing-key";

    private static final String EMAIL =
            "user@example.com";

    private JwtTokenUtils jwtTokenUtils;

    @Mock
    private UserEntity user;

    @Mock
    private UserDetails userDetails;

    @BeforeEach
    void setUp() {
        jwtTokenUtils = new JwtTokenUtils();

        ReflectionTestUtils.setField(
                jwtTokenUtils,
                "secret",
                SECRET
        );
    }

    // ---------------------------------------------------------
    // Generate token
    // ---------------------------------------------------------

    @Test
    void shouldGenerateTokenWithCorrectUsername() {
        when(user.getUsername()).thenReturn(EMAIL);
        when(user.getAuthorities()).thenReturn(List.of());

        String token = jwtTokenUtils.generateToken(user);

        assertThat(jwtTokenUtils.getUsernameFromToken(token))
                .isEqualTo(EMAIL);
    }


    @Test
    void shouldGenerateTokenWithExpirationDateInFuture() {
        when(user.getUsername()).thenReturn(EMAIL);
        when(user.getAuthorities()).thenReturn(List.of());

        String token = jwtTokenUtils.generateToken(user);

        Date expiration = jwtTokenUtils.getExpirationDateFromToken(token);

        assertThat(expiration)
                .isAfter(new Date());
    }

    // ---------------------------------------------------------
    // Username
    // ---------------------------------------------------------

    @Test
    void shouldReturnUsernameFromToken() {
        when(user.getUsername()).thenReturn(EMAIL);
        when(user.getAuthorities()).thenReturn(List.of());

        String token = jwtTokenUtils.generateToken(user);

        assertThat(jwtTokenUtils.getUsernameFromToken(token))
                .isEqualTo(EMAIL);
    }

    // ---------------------------------------------------------
    // Claims
    // ---------------------------------------------------------

    @Test
    void shouldRetrieveCustomClaim() {
        String token = createTokenWithClaims(
        );

        String value = jwtTokenUtils.getClaimFromToken(
                token,
                claims -> claims.get("custom", String.class)
        );

        assertThat(value)
                .isEqualTo("test-value");
    }

    // ---------------------------------------------------------
    // Validate token
    // ---------------------------------------------------------

    @Test
    void shouldValidateTokenForMatchingUser() {
        when(user.getUsername()).thenReturn(EMAIL);
        when(user.getAuthorities()).thenReturn(List.of());

        when(userDetails.getUsername()).thenReturn(EMAIL);

        String token = jwtTokenUtils.generateToken(user);

        assertThat(jwtTokenUtils.validateToken(token, userDetails))
                .isTrue();
    }

    @Test
    void shouldRejectTokenForDifferentUser() {
        when(user.getUsername()).thenReturn(EMAIL);
        when(user.getAuthorities()).thenReturn(List.of());

        when(userDetails.getUsername())
                .thenReturn("other@example.com");

        String token = jwtTokenUtils.generateToken(user);

        assertThat(jwtTokenUtils.validateToken(token, userDetails))
                .isFalse();
    }



    // ---------------------------------------------------------
    // Invalid tokens
    // ---------------------------------------------------------

    @Test
    void shouldRejectTokenSignedWithDifferentSecret() {
        when(user.getUsername()).thenReturn(EMAIL);
        when(user.getAuthorities()).thenReturn(List.of());

        String token = jwtTokenUtils.generateToken(user);

        JwtTokenUtils anotherUtils = new JwtTokenUtils();

        ReflectionTestUtils.setField(
                anotherUtils,
                "secret",
                "another-secret-key-that-is-also-at-least-64-bytes-long"
        );

        assertThatThrownBy(() ->
                anotherUtils.getUsernameFromToken(token)
        )
                .isInstanceOf(Exception.class);
    }

    @Test
    void shouldRejectMalformedToken() {
        assertThatThrownBy(() ->
                jwtTokenUtils.getUsernameFromToken("invalid.jwt.token")
        )
                .isInstanceOf(Exception.class);
    }

    @Test
    void shouldRejectEmptyToken() {
        assertThatThrownBy(() ->
                jwtTokenUtils.getUsernameFromToken("")
        )
                .isInstanceOf(Exception.class);
    }

    // ---------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------

    private String createTokenWithClaims(
            ) {
        SecretKey key = Keys.hmacShaKeyFor(
                SECRET.getBytes(StandardCharsets.UTF_8)
        );

        return Jwts.builder()
                .claim("custom", "test-value")
                .subject(EMAIL)
                .issuedAt(new Date())
                .expiration(new Date(
                        System.currentTimeMillis() + 3600000
                ))
                .signWith(key, Jwts.SIG.HS512)
                .compact();
    }

    // ---------------------------------------------------------
    // Account type claim
    // ---------------------------------------------------------

    @Test
    void shouldMarkCompanyUserTokensAsUserAccounts() {
        UserEntity companyUser = UserEntity.builder().email(EMAIL).build();

        String token = jwtTokenUtils.generateToken(companyUser);

        assertThat(jwtTokenUtils.getAccountTypeFromToken(token)).isEqualTo(JwtTokenUtils.ACCOUNT_TYPE_USER);
    }

    @Test
    void shouldMarkAdministratorTokensAsAdminAccountsWithAdminRole() {
        AdministratorEntity admin = AdministratorEntity.builder().email("admin@vstable.com").build();

        String token = jwtTokenUtils.generateToken(admin);

        assertThat(jwtTokenUtils.getAccountTypeFromToken(token)).isEqualTo(JwtTokenUtils.ACCOUNT_TYPE_ADMIN);
        assertThat(jwtTokenUtils.getUsernameFromToken(token)).isEqualTo("admin@vstable.com");
        assertThat(jwtTokenUtils.<Object>getClaimFromToken(token, claims -> claims.get("role")))
                .isEqualTo(List.of("ROLE_ADMIN"));
    }

    @Test
    void shouldTreatTokensWithoutAccountTypeAsUserAccounts() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String legacyToken = Jwts.builder()
                .subject(EMAIL)
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key, Jwts.SIG.HS512)
                .compact();

        assertThat(jwtTokenUtils.getAccountTypeFromToken(legacyToken)).isEqualTo(JwtTokenUtils.ACCOUNT_TYPE_USER);
    }
}
