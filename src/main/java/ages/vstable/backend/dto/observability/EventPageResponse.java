package ages.vstable.backend.dto.observability;

import java.util.List;

public record EventPageResponse(List<EventResponse> content, int page, int size, long totalElements,
        int totalPages, long errors, List<ModuleFailures> failuresByModule) {
    public record ModuleFailures(String module, long failures) { }
}
