package ages.vstable.backend.service.quote;

/** Valores de {@code provider_quotes.status}. */
public final class ProviderQuoteStatus {

    private ProviderQuoteStatus() {
    }

    public static final String AVAILABLE = "AVAILABLE";
    public static final String FAILED = "FAILED";
    /** Melhor oferta válida da cotação; a única que pode ser confirmada. */
    public static final String SELECTED = "SELECTED";
    /** Oferta confirmada pelo usuário e vinculada a uma execução. */
    public static final String CONFIRMED = "CONFIRMED";
}
