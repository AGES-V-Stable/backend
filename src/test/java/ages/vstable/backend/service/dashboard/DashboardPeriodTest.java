package ages.vstable.backend.service.dashboard;

import ages.vstable.backend.exception.InvalidDateRangeException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashboardPeriodTest {

    @Test
    void bounds_coverWholeDaysInSaoPauloWithInclusiveEndDate() {
        DashboardPeriod period = new DashboardPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

        assertThat(period.from()).isEqualTo(OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.ofHours(-3)));
        assertThat(period.toExclusive()).isEqualTo(OffsetDateTime.of(2026, 2, 1, 0, 0, 0, 0, ZoneOffset.ofHours(-3)));
    }

    @Test
    void bounds_withoutDatesDoNotLimitTheQuery() {
        DashboardPeriod period = new DashboardPeriod(null, null);

        assertThat(period.from()).isBefore(OffsetDateTime.parse("2000-01-01T00:00:00Z"));
        assertThat(period.toExclusive()).isAfter(OffsetDateTime.parse("2100-01-01T00:00:00Z"));
    }

    @Test
    void sameStartAndEndDateIsValid() {
        LocalDate day = LocalDate.of(2026, 5, 10);

        assertThat(new DashboardPeriod(day, day).toExclusive()).isEqualTo(
                OffsetDateTime.of(2026, 5, 11, 0, 0, 0, 0, ZoneOffset.ofHours(-3)));
    }

    @Test
    void datesOutsideTheAcceptedRangeAreRejected() {
        assertThatThrownBy(() -> new DashboardPeriod(LocalDate.of(1999, 12, 31), null))
                .isInstanceOf(InvalidDateRangeException.class)
                .hasMessage("dates must be between 2000-01-01 and 2100-12-31");
        assertThatThrownBy(() -> new DashboardPeriod(null, LocalDate.MAX))
                .isInstanceOf(InvalidDateRangeException.class);
    }

    @Test
    void rangeLimitsAreInclusive() {
        DashboardPeriod period = new DashboardPeriod(LocalDate.of(2000, 1, 1), LocalDate.of(2100, 12, 31));

        assertThat(period.toExclusive()).isEqualTo(OffsetDateTime.of(2101, 1, 1, 0, 0, 0, 0, ZoneOffset.ofHours(-3)));
    }

    @Test
    void startAfterEndIsRejected() {
        assertThatThrownBy(() -> new DashboardPeriod(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 31)))
                .isInstanceOf(InvalidDateRangeException.class);
    }
}
