CREATE TABLE IF NOT EXISTS acquisitions (
    id             SERIAL PRIMARY KEY,
    request_id     TEXT UNIQUE NOT NULL,
    mode           TEXT NOT NULL CHECK (mode IN ('reference', 'captured')),
    node_id        INTEGER NOT NULL,
    seq_num        INTEGER NOT NULL,
    gateway_timestamp TIMESTAMPTZ NOT NULL,

    image_ref      TEXT,
    dataset_id     TEXT,

    capture_id     TEXT,
    image_path     TEXT,

    predicted_label TEXT,
    probabilities   JSONB,
    classified_at   TIMESTAMPTZ,

    retry_attempt  INTEGER NOT NULL DEFAULT 0,
    received_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_acquisitions_node_seq ON acquisitions (node_id, seq_num);