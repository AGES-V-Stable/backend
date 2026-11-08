package ages.vstable.backend.entity.enums;

/** Situação do vínculo de um usuário com uma empresa. */
public enum MembershipStatus {
    /** Vínculo vigente: o usuário opera na empresa conforme seu perfil. */
    ACTIVE,
    /** Vínculo encerrado: o usuário só consulta o próprio acesso, sem dados da empresa. */
    REMOVED
}
