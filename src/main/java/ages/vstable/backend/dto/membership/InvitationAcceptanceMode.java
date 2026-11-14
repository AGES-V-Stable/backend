package ages.vstable.backend.dto.membership;

/** Como o convidado aceita o convite, decidido pela existência de uma conta com o e-mail do convite. */
public enum InvitationAcceptanceMode {
    /** Ainda não há conta: o convidado cria a senha e aceita os termos. */
    REGISTER,
    /** Já há conta: o convidado entra pelo login normal (com 2FA, se exigido) e confirma o aceite. */
    LOGIN
}
