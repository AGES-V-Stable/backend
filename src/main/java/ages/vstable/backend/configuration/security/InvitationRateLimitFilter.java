package ages.vstable.backend.configuration.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;

/**
 * Limite conjunto de 30 consultas ou tentativas de aceite de convite por minuto e por cliente, nas três rotas de
 * {@code /v1/company-invitations}. Roda antes da autenticação e da leitura do corpo, então tentativas sem sessão
 * ou com corpo inválido também contam. O excesso recebe 429 {@code RATE_LIMITED} com Retry-After.
 *
 * <p>O contador fica na memória da instância: na Lambda, cada ambiente de execução conta separadamente e o
 * contador recomeça a cada cold start. Não é um limite global; ele complementa, sem substituir, um limite
 * compartilhado na borda (AWS WAF) e a validação transacional do aceite. O cliente é identificado por
 * {@link ClientIpResolver}.
 */
@Component
public class InvitationRateLimitFilter extends OncePerRequestFilter {

    /**
     * Mesmo matcher dos {@code requestMatchers} da cadeia de segurança: compara o caminho decodificado, então
     * variações codificadas (ex.: {@code %72esolve}) que chegam ao controller também são contadas.
     */
    private static final RequestMatcher LIMITED_REQUESTS = new OrRequestMatcher(
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/v1/company-invitations/resolve"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/v1/company-invitations/accept-new-user"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/v1/company-invitations/accept"));

    private final InvitationAttemptLimiter limiter;
    private final ClientIpResolver clientIpResolver;

    @Autowired
    public InvitationRateLimitFilter(
            @Value("${app.invitation.rate-limit.trusted-proxy-hops:0}") int trustedProxyHops) {
        this(new InvitationAttemptLimiter(Clock.systemUTC()), new ClientIpResolver(trustedProxyHops));
    }

    InvitationRateLimitFilter(InvitationAttemptLimiter limiter, ClientIpResolver clientIpResolver) {
        this.limiter = limiter;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !LIMITED_REQUESTS.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long retryAfterSeconds = limiter.tryAcquire(clientIpResolver.resolve(request));
        if (retryAfterSeconds > 0) {
            response.setStatus(429);
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            // Código e mensagem são constantes, então a formatação direta é segura.
            response.getWriter().write("{\"code\":\"RATE_LIMITED\","
                    + "\"message\":\"Muitas tentativas. Aguarde para tentar de novo.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
