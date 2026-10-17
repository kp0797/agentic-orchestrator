-- Append-only, hash-chained audit log. Single source of truth for run state.
CREATE TABLE run_events (
    run_id    VARCHAR(64)  NOT NULL,
    seq       BIGINT       NOT NULL,
    ts        TIMESTAMP WITH TIME ZONE NOT NULL,
    type      VARCHAR(48)  NOT NULL,
    node_id   VARCHAR(64)  DEFAULT '' NOT NULL,
    data      TEXT         DEFAULT '' NOT NULL,
    prev_hash VARCHAR(64)  NOT NULL,
    hash      VARCHAR(64)  NOT NULL,
    PRIMARY KEY (run_id, seq)
);

CREATE INDEX idx_run_events_type ON run_events (run_id, type);
