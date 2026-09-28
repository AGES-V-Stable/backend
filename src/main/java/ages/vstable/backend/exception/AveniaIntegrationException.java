package ages.vstable.backend.exception;

public class AveniaIntegrationException extends RuntimeException {

    private final Integer upstreamStatus;

    public AveniaIntegrationException(String message, Throwable cause) {
        this(message, cause, null);
    }

    /**
     * @param upstreamStatus código HTTP devolvido pela Avenia, quando a falha veio de uma
     *                       resposta de erro dela (não de um problema de rede/infraestrutura).
     *                       Usado por {@link GlobalExceptionHandler} para diferenciar uma
     *                       rejeição de negócio (ex.: CPF já usado) de uma falha real de
     *                       comunicação com a Avenia.
     */
    public AveniaIntegrationException(String message, Throwable cause, Integer upstreamStatus) {
        super(message, cause);
        this.upstreamStatus = upstreamStatus;
    }

    public Integer getUpstreamStatus() {
        return upstreamStatus;
    }

    public boolean isUpstreamClientError() {
        return upstreamStatus != null && upstreamStatus >= 400 && upstreamStatus < 500;
    }
}
