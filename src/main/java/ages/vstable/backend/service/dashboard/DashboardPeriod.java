package ages.vstable.backend.service.dashboard;

import ages.vstable.backend.exception.InvalidDateRangeException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Período de consulta do dashboard em dias civis de America/Sao_Paulo, com {@code endDate} inclusivo.
 * Data ausente não limita a consulta; os limites técnicos evitam parâmetros nulos nas queries.
 */
public record DashboardPeriod(LocalDate startDate, LocalDate endDate) {

    public static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Sao_Paulo");

    /** Limita as séries mensais a cerca de 1.200 pontos e evita estouro de data em {@code plusDays}. */
    static final LocalDate MIN_DATE = LocalDate.of(2000, 1, 1);
    static final LocalDate MAX_DATE = LocalDate.of(2100, 12, 31);

    private static final OffsetDateTime EARLIEST = OffsetDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime LATEST = OffsetDateTime.of(9999, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    public DashboardPeriod {
        if (outOfRange(startDate) || outOfRange(endDate)) {
            throw new InvalidDateRangeException("dates must be between " + MIN_DATE + " and " + MAX_DATE);
        }
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new InvalidDateRangeException();
        }
    }

    private static boolean outOfRange(LocalDate date) {
        return date != null && (date.isBefore(MIN_DATE) || date.isAfter(MAX_DATE));
    }

    public OffsetDateTime from() {
        return startDate == null ? EARLIEST : startDate.atStartOfDay(BUSINESS_ZONE).toOffsetDateTime();
    }

    public OffsetDateTime toExclusive() {
        return endDate == null ? LATEST : endDate.plusDays(1).atStartOfDay(BUSINESS_ZONE).toOffsetDateTime();
    }
}
