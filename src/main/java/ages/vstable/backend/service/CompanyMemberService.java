package ages.vstable.backend.service;

import ages.vstable.backend.dto.membership.MemberListItemResponse;
import ages.vstable.backend.dto.membership.MemberListResponse;
import ages.vstable.backend.entity.CompanyInvitationEntity;
import ages.vstable.backend.entity.CompanyMembershipEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.InvitationStatus;
import ages.vstable.backend.entity.enums.MembershipStatus;
import ages.vstable.backend.exception.InvalidParameterException;
import ages.vstable.backend.repository.CompanyInvitationRepository;
import ages.vstable.backend.repository.CompanyMembershipRepository;
import ages.vstable.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Collator;
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
