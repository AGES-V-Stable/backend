package ages.vstable.backend.dto.membership;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Membros ativos e convites pendentes da empresa, paginados")
public record MemberListResponse(
        List<MemberListItemResponse> items,
        int page,
        int size,
        @Schema(description = "Igual a summary.peopleCount")
        long totalElements,
        int totalPages,
        Summary summary) {

    @Schema(description = "Contagens do conjunto completo, não só da página")
    public record Summary(
            long activeMembers,
            long pendingInvitations,
            @Schema(description = "activeMembers + pendingInvitations")
            long peopleCount) {
    }
}
