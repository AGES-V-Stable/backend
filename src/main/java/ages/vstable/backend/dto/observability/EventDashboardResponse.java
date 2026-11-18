package ages.vstable.backend.dto.observability;

public record EventDashboardResponse(EventSummaryResponse summary, EventPageResponse events) { }
