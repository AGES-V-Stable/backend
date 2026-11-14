package ages.vstable.backend.service.exchangerate;

public enum ExchangeRateStatus {
    /** Cotação dentro do TTL ou obtida agora da Avenia. */
    CURRENT,
    /** A Avenia falhou; é a última cotação salva, mais antiga que o TTL. */
    STALE,
    /** A Avenia falhou e nunca houve cotação salva para o par. */
    UNAVAILABLE
}
