package ages.vstable.backend.service.observability;

import ages.vstable.backend.dto.observability.BrowserEventRequest;
import ages.vstable.backend.entity.ObservabilityEventEntity;
import ages.vstable.backend.logging.OperationalLog;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ObservabilityRecorderTest {
    @Test
    @SuppressWarnings("unchecked")
    void persistsCommittedBusinessEventWithInternalIdentityAndCorrelation() {
        var writer = mock(ObservabilityWriter.class);
        var recorder = new ObservabilityRecorder(writer, 30);
        recorder.initialize();
        UUID actor = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        UUID entity = UUID.randomUUID();
        try {
            MDC.put("actorId", actor.toString()); MDC.put("requestId", correlation.toString());
            OperationalLog.milestone("company.creation.completed", "success", "company", entity);
            recorder.flush();
            ArgumentCaptor<List<ObservabilityEventEntity>> captured = ArgumentCaptor.forClass(List.class);
            verify(writer, timeout(2000)).write(captured.capture());
            var event = captured.getValue().getFirst();
            assertThat(event.getEntityId()).isEqualTo(entity);
            assertThat(event.getActorId()).isEqualTo(actor);
            assertThat(event.getCorrelationId()).isEqualTo(correlation);
        } finally { MDC.clear(); recorder.close(); }
    }

    @Test
    void databaseFailureDoesNotEscapeOrInterruptOperations() {
        var writer = mock(ObservabilityWriter.class);
        doThrow(new IllegalStateException("database unavailable")).when(writer).write(anyList());
        var recorder = new ObservabilityRecorder(writer, 30);
        recorder.browser(new BrowserEventRequest(UUID.randomUUID(), UUID.randomUUID(),
                BrowserEventRequest.Kind.UI_ERROR, BrowserEventRequest.Module.APPLICATION), UUID.randomUUID());
        assertThatCode(recorder::flush).doesNotThrowAnyException();
        assertThat(recorder.droppedEvents()).isEqualTo(1);
    }

    @Test
    void queueHasBoundedMemory() {
        var recorder = new ObservabilityRecorder(mock(ObservabilityWriter.class), 30);
        for (int i = 0; i < 2050; i++) recorder.browser(new BrowserEventRequest(UUID.randomUUID(), UUID.randomUUID(),
                BrowserEventRequest.Kind.NETWORK_FAILURE, BrowserEventRequest.Module.TRANSFERS), UUID.randomUUID());
        assertThat(recorder.droppedEvents()).isEqualTo(2);
    }
}
