package ages.vstable.backend.entity.enums;

/** Estados do convite de membro. O link não expira por tempo: só o aceite, a revogação ou a regeneração o encerram. */
public enum InvitationStatus {
    /** Convite aguardando aceite; o link vigente ainda pode ser copiado pelo proprietário. */
    PENDING,
    /** Convite aceito: um vínculo foi criado e o link deixou de ser recuperável. */
    ACCEPTED,
    /** Convite revogado pelo proprietário: o link deixou de ser válido. */
    REVOKED
}
