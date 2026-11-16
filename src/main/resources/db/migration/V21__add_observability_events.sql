CREATE TABLE observability_events (
    id UUID PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL,
    severity VARCHAR(8) NOT NULL,
    module VARCHAR(80) NOT NULL,
    action VARCHAR(120) NOT NULL,
    actor_id UUID,
    entity_type VARCHAR(40),
    entity_id UUID,
    outcome VARCHAR(80) NOT NULL,
    failed BOOLEAN NOT NULL,
    duration_ms BIGINT,
    message VARCHAR(160) NOT NULL,
    correlation_id UUID,
    source VARCHAR(16) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}',
    client_event_id UUID,
    CONSTRAINT observability_browser_dedup UNIQUE (actor_id, client_event_id)
);
CREATE INDEX observability_time_idx ON observability_events (occurred_at DESC, id DESC);
CREATE INDEX observability_severity_time_idx ON observability_events (severity, occurred_at DESC);
CREATE INDEX observability_module_time_idx ON observability_events (module, occurred_at DESC);
CREATE INDEX observability_action_time_idx ON observability_events (action, occurred_at DESC);
CREATE INDEX observability_outcome_time_idx ON observability_events (outcome, occurred_at DESC);
CREATE INDEX observability_correlation_idx ON observability_events (correlation_id, occurred_at DESC);
