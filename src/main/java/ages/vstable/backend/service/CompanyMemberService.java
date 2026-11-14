package ages.vstable.backend.service;

import ages.vstable.backend.dto.membership.MemberListItemResponse;
import ages.vstable.backend.dto.membership.MemberListResponse;
import ages.vstable.backend.dto.membership.MemberResponse;
import ages.vstable.backend.dto.membership.InviteRole;
import ages.vstable.backend.entity.CompanyInvitationEntity;
import ages.vstable.backend.entity.CompanyMembershipEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.InvitationStatus;
import ages.vstable.backend.entity.enums.MembershipStatus;
import ages.vstable.backend.exception.InvalidParameterException;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.exception.PreconditionFailedException;
import ages.vstable.backend.repository.CompanyInvitationRepository;
import ages.vstable.backend.repository.CompanyMembershipRepository;
import ages.vstable.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Collator;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CompanyMemberService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Logger AUDIT = LoggerFactory.getLogger(CompanyMemberService.class);

    /** Ordem alfabética do português: ignora maiúsculas e minúsculas e não joga "Álvaro" depois de "Zé". */
    private static final Collator NAME_COLLATOR = nameCollator();

    private final CompanyMembershipRepository membershipRepository;
    private final CompanyInvitationRepository invitationRepository;
    private final UserRepository userRepository;

    /**
     * Membros ativos e convites pendentes da empresa: o proprietário primeiro, os demais por nome sem
     * diferenciar maiúsculas e, no empate, membro antes de convite e depois pelo id. Uma empresa tem poucas
     * pessoas, então a lista é montada e paginada em memória a partir de duas consultas.
     */
    @Transactional(readOnly = true)
    public MemberListResponse list(UUID companyId, int page, int size) {
        if (page < 0) {
            throw new InvalidParameterException("O parâmetro 'page' deve ser maior ou igual a 0.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidParameterException("O parâmetro 'size' deve estar entre 1 e " + MAX_PAGE_SIZE + ".");
        }

        List<CompanyMembershipEntity> memberships =
                membershipRepository.findByCompanyIdAndStatus(companyId, MembershipStatus.ACTIVE);
        List<CompanyInvitationEntity> invitations =
                invitationRepository.findByCompanyIdAndStatus(companyId, InvitationStatus.PENDING);
        Map<UUID, UserEntity> users = loadUsers(memberships, invitations);

        List<Entry> entries = new ArrayList<>();
        for (CompanyMembershipEntity membership : memberships) {
            UserEntity user = users.get(membership.getUserId());
            entries.add(new Entry(
                    membership.getRole() == CompanyRole.OWNER,
                    user.getFullName(),
                    MemberListItemResponse.Kind.MEMBER,
                    membership.getId(),
                    MemberListItemResponse.ofMember(MembershipResponseMapper.member(membership, user))));
        }
        for (CompanyInvitationEntity invitation : invitations) {
            UserEntity creator = users.get(invitation.getCreatedBy());
            entries.add(new Entry(
                    false,
                    invitation.getName(),
                    MemberListItemResponse.Kind.INVITATION,
                    invitation.getId(),
                    MemberListItemResponse.ofInvitation(MembershipResponseMapper.invitation(invitation, creator))));
        }
        entries.sort(Entry.ORDER);

        long total = entries.size();
        int from = (int) Math.min((long) page * size, total);
        int to = (int) Math.min(from + (long) size, total);
        List<MemberListItemResponse> items = entries.subList(from, to).stream().map(Entry::item).toList();

        return new MemberListResponse(
                items,
                page,
                size,
                total,
                (int) Math.ceil(total / (double) size),
                new MemberListResponse.Summary(memberships.size(), invitations.size(), total));
    }

    /**
     * O vinculo e bloqueado no banco para que duas alteracoes com o mesmo ETag nao
     * possam persistir versoes divergentes. O JWT nao e a fonte das permissoes.
     */
    @Transactional
    public MemberResponse changeRole(UUID companyId, UUID memberId, long expectedVersion,
                                     InviteRole newRole, UUID actorUserId) {
        CompanyMembershipEntity member = lockedMember(companyId, memberId);
        protectOwner(member);
        if (member.getStatus() != MembershipStatus.ACTIVE) {
            throw new ConflictException("MEMBERSHIP_NOT_ACTIVE", "Este membro nao possui vinculo ativo.");
        }
        requireVersion(member, expectedVersion);
        if (member.getRole() != newRole.toCompanyRole()) {
            member.setRole(newRole.toCompanyRole());
            member.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            member.setVersion(member.getVersion() + 1);
            membershipRepository.saveAndFlush(member);
            auditAfterCommit("company.member.role_updated", actorUserId, companyId, memberId);
        }
        UserEntity user = userRepository.findById(member.getUserId())
                .orElseThrow(() -> new IllegalStateException("User linked to membership not found"));
        return MembershipResponseMapper.member(member, user);
    }

    /**
     * Revoga somente o vinculo. Nao apaga usuario, saldo, KYC, historicos ou
     * transacoes; o filtro JWT recarrega a situacao do vinculo a cada requisicao.
     */
    @Transactional
    public void revoke(UUID companyId, UUID memberId, long expectedVersion, UUID actorUserId) {
        CompanyMembershipEntity member = lockedMember(companyId, memberId);
        if (member.getStatus() == MembershipStatus.REMOVED) {
            return; // DELETE idempotente, inclusive com ETag antigo.
        }
        protectOwner(member);
        requireVersion(member, expectedVersion);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        member.setStatus(MembershipStatus.REMOVED);
        member.setRemovedAt(now);
        member.setUpdatedAt(now);
        member.setVersion(member.getVersion() + 1);
        membershipRepository.saveAndFlush(member);
        auditAfterCommit("company.member.revoked", actorUserId, companyId, memberId);
    }

    private CompanyMembershipEntity lockedMember(UUID companyId, UUID memberId) {
        return membershipRepository.findForUpdate(memberId, companyId)
                .orElseThrow(() -> new NotFoundException("MEMBER_NOT_FOUND", "Membro nao encontrado."));
    }

    private static void protectOwner(CompanyMembershipEntity member) {
        if (member.getRole() == CompanyRole.OWNER) {
            throw new ConflictException("OWNER_PROTECTED", "O proprietario nao pode ser alterado ou removido.");
        }
    }

    private static void requireVersion(CompanyMembershipEntity member, long expectedVersion) {
        if (member.getVersion() != expectedVersion) {
            throw new PreconditionFailedException("RESOURCE_VERSION_MISMATCH",
                    "O membro foi alterado. Atualize a lista e tente novamente.");
        }
    }

    /** O evento inclui somente IDs tecnicos; nunca nome, email, JWT ou documentos. */
    private static void auditAfterCommit(String action, UUID actorId, UUID companyId, UUID memberId) {
        Runnable emit = () -> AUDIT.atInfo()
                .addKeyValue("event", action)
                .addKeyValue("actorUserId", actorId)
                .addKeyValue("companyId", companyId)
                .addKeyValue("membershipId", memberId)
                .log("Company membership access changed");
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { emit.run(); }
            });
        } else {
            emit.run();
        }
    }

    private static Collator nameCollator() {
        Collator collator = Collator.getInstance(Locale.forLanguageTag("pt-BR"));
        collator.setStrength(Collator.SECONDARY);
        return collator;
    }

    private Map<UUID, UserEntity> loadUsers(List<CompanyMembershipEntity> memberships,
                                            List<CompanyInvitationEntity> invitations) {
        Set<UUID> ids = new HashSet<>();
        memberships.forEach(membership -> ids.add(membership.getUserId()));
        invitations.forEach(invitation -> ids.add(invitation.getCreatedBy()));
        return userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(UserEntity::getId, Function.identity()));
    }

    private record Entry(boolean owner, String name, MemberListItemResponse.Kind kind, UUID id,
                         MemberListItemResponse item) {

        static final Comparator<Entry> ORDER = Comparator
                .comparing((Entry entry) -> !entry.owner)
                .thenComparing(entry -> entry.name, NAME_COLLATOR)
                .thenComparing(Entry::kind)
                .thenComparing(Entry::id);
    }
}
