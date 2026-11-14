package ages.vstable.backend.configuration.security;

import jakarta.servlet.http.HttpServletRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Identifica o cliente para o limite de tentativas sem confiar em cabeçalhos que o próprio cliente controla.
 *
 * <p>Cada proxy confiável acrescenta ao fim do {@code X-Forwarded-For} o endereço de quem se conectou a ele. Com
 * {@code trustedProxyHops = N}, o cliente é o N-ésimo item a partir da direita: os N-1 itens seguintes foram
 * acrescentados pelos outros proxies confiáveis, e tudo à esquerda pode ter sido enviado pelo cliente.
 *
 * <ul>
 *   <li>{@code 0}: o cabeçalho é ignorado e vale o endereço da conexão (Docker local, acesso direto).</li>
 *   <li>{@code 1}: um proxy confiável acrescenta o IP do cliente, como o API Gateway com endpoint regional
 *   chamado diretamente pelo navegador.</li>
 *   <li>{@code 2}: dois proxies, como CloudFront (ou endpoint edge-optimized) seguido do API Gateway.</li>
 * </ul>
 *
 * <p>O cabeçalho só é lido quando a conexão chega pelo loopback, que na Lambda é o AWS Lambda Web Adapter no
 * mesmo ambiente de execução; qualquer outra conexão poderia forjá-lo. Sem item confiável ou com um item que não
 * parece um IP, vale o endereço da conexão: na Lambda isso agrupa essas requisições numa única chave, o que
 * limita mais e nunca menos.
 */
final class ClientIpResolver {

    static final String FORWARDED_FOR = "X-Forwarded-For";
    static final int MAX_TRUSTED_PROXY_HOPS = 5;

    /** Literal IPv4 ou IPv6 (sem resolver nomes): só dígitos hexadecimais, pontos e dois-pontos. */
    private static final Pattern IP_LITERAL = Pattern.compile("(?=.*[.:])[0-9A-Fa-f:.]{3,45}");

    private final int trustedProxyHops;

    ClientIpResolver(int trustedProxyHops) {
        if (trustedProxyHops < 0 || trustedProxyHops > MAX_TRUSTED_PROXY_HOPS) {
            throw new IllegalArgumentException(
                    "app.invitation.rate-limit.trusted-proxy-hops deve estar entre 0 e " + MAX_TRUSTED_PROXY_HOPS);
        }
        this.trustedProxyHops = trustedProxyHops;
    }

    String resolve(HttpServletRequest request) {
        String connection = request.getRemoteAddr();
        if (trustedProxyHops == 0 || !isLoopback(connection)) {
            return connection;
        }
        List<String> hops = forwardedHops(request);
        if (hops.size() < trustedProxyHops) {
            return connection;
        }
        String client = hops.get(hops.size() - trustedProxyHops);
        return IP_LITERAL.matcher(client).matches() ? client : connection;
    }

    /** Itens de todos os cabeçalhos X-Forwarded-For, na ordem recebida; itens vazios mantêm a posição. */
    private static List<String> forwardedHops(HttpServletRequest request) {
        List<String> hops = new ArrayList<>();
        for (String header : Collections.list(request.getHeaders(FORWARDED_FOR))) {
            for (String hop : header.split(",", -1)) {
                hops.add(hop.strip());
            }
        }
        return hops;
    }

    private static boolean isLoopback(String address) {
        return address != null
                && (address.startsWith("127.") || "::1".equals(address) || "0:0:0:0:0:0:0:1".equals(address));
    }
}
