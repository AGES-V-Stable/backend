package ages.vstable.backend.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Versão vigente dos termos de uso e da política de privacidade, com os endereços públicos de cada documento.
 * O aceite do convite só vale para a versão vigente, que é gravada junto com o instante do aceite. Valores
 * inválidos impedem a inicialização, em vez de falhar no momento do aceite.
 */
@Component
public class TermsProperties {

    /** Tamanho da coluna company_invitations.terms_version. */
    static final int MAX_VERSION_LENGTH = 50;

    private final String version;
    private final String termsUrl;
    private final String privacyUrl;

    public TermsProperties(@Value("${app.terms.version}") String version,
                           @Value("${app.terms.terms-url}") String termsUrl,
                           @Value("${app.terms.privacy-url}") String privacyUrl) {
        if (version == null || version.isBlank() || version.length() > MAX_VERSION_LENGTH
                || !version.equals(version.strip())) {
            throw new IllegalStateException("app.terms.version deve ter de 1 a " + MAX_VERSION_LENGTH
                    + " caracteres, sem espaços nas pontas");
        }
        this.version = version;
        this.termsUrl = requireHttpUrl("app.terms.terms-url", termsUrl);
        this.privacyUrl = requireHttpUrl("app.terms.privacy-url", privacyUrl);
    }

    public String version() {
        return version;
    }

    public String termsUrl() {
        return termsUrl;
    }

    public String privacyUrl() {
        return privacyUrl;
    }

    private static String requireHttpUrl(String property, String value) {
        try {
            URI uri = new URI(value == null ? "" : value);
            String scheme = uri.getScheme();
            if (("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) && uri.getHost() != null) {
                return withoutRepeatedSlashes(uri);
            }
        } catch (URISyntaxException e) {
            // segue para o erro abaixo
        }
        throw new IllegalStateException(property + " deve ser uma URL http(s) absoluta");
    }

    /**
     * O padrão das URLs é {@code ${INVITATION_FRONTEND_ORIGIN}/termos-de-uso}; uma origem configurada com barra no
     * fim geraria {@code //termos-de-uso}. Barras repetidas no caminho são reduzidas a uma.
     */
    private static String withoutRepeatedSlashes(URI uri) {
        String path = uri.getRawPath() == null ? "" : uri.getRawPath().replaceAll("/{2,}", "/");
        return uri.getScheme() + "://" + uri.getRawAuthority() + path
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
                + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
    }
}
