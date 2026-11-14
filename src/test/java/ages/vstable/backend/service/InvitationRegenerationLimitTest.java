package ages.vstable.backend.service;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InvitationRegenerationLimitTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 11, 12, 0, 0, 0, ZoneOffset.UTC);

    private static OffsetDateTime ago(long seconds) {
        return NOW.minusSeconds(seconds);
    }

    @Test
    void retryAfter_noGenerationInTheLastHour_isAllowed() {
        assertThat(InvitationRegenerationLimit.retryAfterSeconds(List.of(), NOW)).isZero();
    }

    @Test
    void retryAfter_lastGenerationLessThan60SecondsAgo_waitsTheRest() {
        // Arrange: só a criação, 10 segundos atrás
        List<OffsetDateTime> generations = List.of(ago(10));

        // Act / Assert
        assertThat(InvitationRegenerationLimit.retryAfterSeconds(generations, NOW)).isEqualTo(50);
        assertThat(InvitationRegenerationLimit.retryAfterSeconds(List.of(ago(59)), NOW)).isEqualTo(1);
        assertThat(InvitationRegenerationLimit.retryAfterSeconds(List.of(ago(0)), NOW)).isEqualTo(60);
    }

    @Test
    void retryAfter_exactly60SecondsAfterTheLastGeneration_isAllowed() {
        assertThat(InvitationRegenerationLimit.retryAfterSeconds(List.of(ago(60)), NOW)).isZero();
        assertThat(InvitationRegenerationLimit.retryAfterSeconds(List.of(ago(61)), NOW)).isZero();
    }

    @Test
    void retryAfter_roundsAFractionOfASecondUp() {
        // Arrange: 59,5 segundos desde a última geração
        List<OffsetDateTime> generations = List.of(NOW.minusSeconds(59).minusNanos(500_000_000));

        // Act / Assert
        assertThat(InvitationRegenerationLimit.retryAfterSeconds(generations, NOW)).isEqualTo(1);
    }

    @Test
    void retryAfter_fourGenerationsInTheHourAndTheLastOneOld_isAllowed() {
        // Arrange
        List<OffsetDateTime> generations = List.of(ago(3000), ago(2400), ago(1800), ago(120));

        // Act / Assert
        assertThat(InvitationRegenerationLimit.retryAfterSeconds(generations, NOW)).isZero();
    }

    @Test
    void retryAfter_fiveGenerationsInTheHour_waitsUntilTheOldestLeavesTheWindow() {
        // Arrange: a mais antiga saiu há 50 minutos e as demais são mais novas
        List<OffsetDateTime> generations = List.of(ago(3000), ago(2400), ago(1800), ago(900), ago(300));

        // Act / Assert: 3600 - 3000 = 600 segundos, mais do que os 60 segundos desde a última
        assertThat(InvitationRegenerationLimit.retryAfterSeconds(generations, NOW)).isEqualTo(600);
    }

    @Test
    void retryAfter_fiveGenerationsAndTheOldestAboutToLeave_stillRespectsTheMinimumInterval() {
        // Arrange: a mais antiga sai em 1 segundo, mas a última foi há 30 segundos
        List<OffsetDateTime> generations = List.of(ago(3599), ago(2400), ago(1800), ago(900), ago(30));

        // Act / Assert: vale o maior dos dois esperas
        assertThat(InvitationRegenerationLimit.retryAfterSeconds(generations, NOW)).isEqualTo(30);
    }

    @Test
    void retryAfter_unsortedGenerations_giveTheSameAnswer() {
        // Arrange
        List<OffsetDateTime> generations = List.of(ago(300), ago(3000), ago(900), ago(1800), ago(2400));

        // Act / Assert
        assertThat(InvitationRegenerationLimit.retryAfterSeconds(generations, NOW)).isEqualTo(600);
    }

    @Test
    void retryAfter_moreGenerationsThanTheMaximum_waitsUntilFewerThanFiveRemain() {
        // Arrange: seis na janela (não deveria ocorrer); só a 2ª mais antiga precisa sair para restarem quatro
        List<OffsetDateTime> generations = List.of(ago(3500), ago(3400), ago(2000), ago(1500), ago(900), ago(300));

        // Act / Assert: a de 3400 s atrás sai em 200 s
        assertThat(InvitationRegenerationLimit.retryAfterSeconds(generations, NOW)).isEqualTo(200);
    }
}
