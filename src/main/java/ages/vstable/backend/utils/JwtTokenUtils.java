package ages.vstable.backend.utils;

import ages.vstable.backend.entity.AdministratorEntity;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.io.Serial;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class JwtTokenUtils implements Serializable {

    @Serial
    private static final long serialVersionUID = -2550185165626007488L;
    public static final long JWT_TOKEN_VALIDITY = 5 * 60 * 60;
    public static final long CHALLENGE_TOKEN_VALIDITY = 5 * 60;
    public static final String ACCOUNT_TYPE_CLAIM = "account_type";
    public static final String ACCOUNT_TYPE_ADMIN = "ADMIN";
    public static final String ACCOUNT_TYPE_USER = "USER";
    public static final String PURPOSE_CLAIM = "purpose";
    public static final String PURPOSE_2FA_CHALLENGE = "2FA_CHALLENGE";

    
    @Value("${api.auth.jwt.secret}")
    private String secret;

    //retrieve username from jwt token
    public String getUsernameFromToken(String token) {
        return getClaimFromToken(token, Claims::getSubject);
    }

    //retrieve expiration date from jwt token
    public Date getExpirationDateFromToken(String token) {
        return getClaimFromToken(token, Claims::getExpiration);
    }

    public <T> T getClaimFromToken(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = getAllClaimsFromToken(token);
        return claimsResolver.apply(claims);
    }
    //for retrieving any information from token we will need the secret key
    private Claims getAllClaimsFromToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    //check if the token has expired
    private Boolean isTokenExpired(String token) {
        final Date expiration = getExpirationDateFromToken(token);
        return expiration.before(new Date());
    }

    //retrieve account type (ADMIN or USER) from jwt token; tokens without the claim are USER tokens
    public String getAccountTypeFromToken(String token) {
        String accountType = getClaimFromToken(token, claims -> claims.get(ACCOUNT_TYPE_CLAIM, String.class));
        return accountType == null ? ACCOUNT_TYPE_USER : accountType;
    }

    //generate token for a company user or an administrator
    public String generateToken(UserDetails principal) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", principal.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList()));
        claims.put(ACCOUNT_TYPE_CLAIM,
                principal instanceof AdministratorEntity ? ACCOUNT_TYPE_ADMIN : ACCOUNT_TYPE_USER);

        return doGenerateToken(claims, principal.getUsername());
    }

    //while creating the token -
    //1. Define  claims of the token, like Issuer, Expiration, Subject, and the ID
    //2. Sign the JWT using the HS512 algorithm and secret key.
    //3. According to JWS Compact Serialization(https://tools.ietf.org/html/draft-ietf-jose-json-web-signature-41#section-3.1)
    //   compaction of the JWT to a URL-safe string
    private String doGenerateToken(Map<String, Object> claims, String subject) {
        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + JWT_TOKEN_VALIDITY * 1000))
                .signWith(getSigningKey(), Jwts.SIG.HS512)
            .compact();
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(
                secret.getBytes(StandardCharsets.UTF_8)
        );
    }


    /**
     * Gera um token temporário (5 min) exclusivo para a 2ª etapa do login (desafio TOTP).
     * Não concede acesso às rotas protegidas da API.
     */
    public String generateChallengeToken(UserDetails principal) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(PURPOSE_CLAIM, PURPOSE_2FA_CHALLENGE);
        claims.put(ACCOUNT_TYPE_CLAIM,
                principal instanceof AdministratorEntity ? ACCOUNT_TYPE_ADMIN : ACCOUNT_TYPE_USER);

        return Jwts.builder()
            .claims(claims)
            .subject(principal.getUsername())
            .issuedAt(new Date(System.currentTimeMillis()))
            .expiration(new Date(System.currentTimeMillis() + CHALLENGE_TOKEN_VALIDITY * 1000))
            .signWith(getSigningKey(), Jwts.SIG.HS512)
            .compact();
    }


     /**
     * Verifica se o token possui a claim purpose = 2FA_CHALLENGE e não está expirado.
     */

     public boolean validateChallengeToken(String token){
        try {
            String purpose = getClaimFromToken(token, claims -> claims.get(PURPOSE_CLAIM, String.class));
            return PURPOSE_2FA_CHALLENGE.equals(purpose) && !isTokenExpired(token);
        } catch (Exception e) {
            return false;
        }
     }
    //validate token
    public Boolean validateToken(String token, UserDetails userDetails) {
        final String username = getUsernameFromToken(token);
        final String purpose = getClaimFromToken(token, claims -> claims.get(PURPOSE_CLAIM, String.class));
        // Impede que um challengeToken seja usado como token de sessão na API!
        if (PURPOSE_2FA_CHALLENGE.equals(purpose)){
            return false;
        }
        return (username.equals(userDetails.getUsername()) && !isTokenExpired(token));
    }
}