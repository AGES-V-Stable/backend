package ages.vstable.backend.logging;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.web.servlet.HandlerMapping;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Cada ponto de instrumentação de {@link OperationalLog} emite a métrica EMF correspondente. */
class OperationalLogMetricsTest {

    private final List<String> lines = new ArrayList<>();

    private final AbstractPlatformTransactionManager transactions = new AbstractPlatformTransactionManager() {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    };

    @BeforeEach
    void setUp() {
        EmfMetrics.configure(true, "VStable/test");
        EmfMetrics.useSink(lines::add);
    }

    @AfterEach
    void tearDown() {
        EmfMetrics.configure(false, "VStable");
        EmfMetrics.useSink(null);
    }

    @Test
    void provider_emitsCallCountAndDurationByProviderAndOutcome() {
        // Act
        OperationalLog.provider("blindpay", "get.Quote", "timeout", null, System.nanoTime());

        // Assert
        String line = lines.getFirst();
        assertThat(JsonPath.<String>read(line, "$.Provider")).isEqualTo("blindpay");
        assertThat(JsonPath.<String>read(line, "$.Outcome")).isEqualTo("timeout");
        assertThat(JsonPath.<Integer>read(line, "$.ProviderCall")).isEqualTo(1);
        assertThat(line).contains("\"ProviderCallDuration\":").doesNotContain("get.Quote");
    }

    @Test
    void milestone_emitsOnlyAfterCommitAndNeverOnRollback() {
        // Arrange
        var committed = transactions.getTransaction(new DefaultTransactionDefinition());
        OperationalLog.milestone("onboarding.completed", "success");
        assertThat(lines).isEmpty();

        // Act
        transactions.commit(committed);

        // Assert
        assertThat(lines).hasSize(1);
        assertThat(JsonPath.<String>read(lines.getFirst(), "$.Operation")).isEqualTo("onboarding.completed");
        assertThat(JsonPath.<Integer>read(lines.getFirst(), "$.BusinessOperation")).isEqualTo(1);

        var rolledBack = transactions.getTransaction(new DefaultTransactionDefinition());
        OperationalLog.milestone("transfer.creation.completed", "success");
        transactions.rollback(rolledBack);
        assertThat(lines).hasSize(1);
    }

    @Test
    void quoteAggregation_emitsOutcomeDurationAndProviderTimeouts() {
        // Act
        OperationalLog.quoteAggregation("partial", System.nanoTime(), 2);

        // Assert
        String line = lines.getFirst();
        assertThat(JsonPath.<String>read(line, "$.Outcome")).isEqualTo("partial");
        assertThat(JsonPath.<Integer>read(line, "$.QuoteProviderTimeouts")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(line, "$.QuoteAggregation")).isEqualTo(1);
    }

    @Test
    void exchangeRateRefresh_emitsByPairAndOutcome() {
        // Act
        OperationalLog.exchangeRateRefresh("BRL/USDC", "failure");

        // Assert
        assertThat(JsonPath.<String>read(lines.getFirst(), "$.Pair")).isEqualTo("BRL/USDC");
        assertThat(JsonPath.<String>read(lines.getFirst(), "$.Outcome")).isEqualTo("failure");
    }

    @Test
    void authentication_emitsRejectionByReasonWithoutAnyIdentity() {
        // Act
        OperationalLog.authentication("invalid_token");

        // Assert
        assertThat(JsonPath.<String>read(lines.getFirst(), "$.Outcome")).isEqualTo("invalid_token");
        assertThat(JsonPath.<Integer>read(lines.getFirst(), "$.AuthenticationRejected")).isEqualTo(1);
    }

    @Test
    void unexpected_emitsErrorCountWithoutMessageOrExceptionDetails() {
        // Act
        OperationalLog.unexpected(new IllegalStateException("synthetic-secret"));

        // Assert
        assertThat(JsonPath.<Integer>read(lines.getFirst(), "$.UnexpectedError")).isEqualTo(1);
        assertThat(lines.getFirst()).doesNotContain("synthetic-secret", "IllegalStateException");
    }

    @Test
    void reconciliationRun_isAHeartbeatEvenWhenThereIsNothingToReconcile() {
        // Act
        OperationalLog.reconciliationRun(0, 0, System.nanoTime());

        // Assert
        String line = lines.getFirst();
        assertThat(JsonPath.<Integer>read(line, "$.ReconciliationRun")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(line, "$.ReconciliationExecutionsFound")).isZero();
        assertThat(JsonPath.<Integer>read(line, "$.ReconciliationFailures")).isZero();
    }

    @Test
    void reconciliationRun_reportsFoundAndFailedExecutions() {
        // Act
        OperationalLog.reconciliationRun(5, 2, System.nanoTime());

        // Assert
        assertThat(JsonPath.<Integer>read(lines.getFirst(), "$.ReconciliationExecutionsFound")).isEqualTo(5);
        assertThat(JsonPath.<Integer>read(lines.getFirst(), "$.ReconciliationFailures")).isEqualTo(2);
    }

    @Test
    void httpRequest_filterEmitsRouteAndStatusClassButSkipsHealthProbes() throws Exception {
        // Arrange
        RequestLoggingFilter filter = new RequestLoggingFilter();
        FilterChain ok = (request, response) -> {
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/v1/transfers/{id}");
            ((MockHttpServletResponse) response).setStatus(404);
        };

        // Act
        filter.doFilter(new MockHttpServletRequest("GET", "/v1/transfers/123"), new MockHttpServletResponse(), ok);
        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health/readiness"), new MockHttpServletResponse(), ok);

        // Assert
        assertThat(lines).hasSize(1);
        assertThat(JsonPath.<String>read(lines.getFirst(), "$.Route")).isEqualTo("/v1/transfers/{id}");
        assertThat(JsonPath.<String>read(lines.getFirst(), "$.StatusClass")).isEqualTo("4xx");
        assertThat(lines.getFirst()).doesNotContain("123");
    }
}
