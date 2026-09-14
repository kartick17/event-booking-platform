CREATE TABLE events (
    id              BIGSERIAL PRIMARY KEY,
    title           VARCHAR(200)   NOT NULL,
    description     TEXT,
    venue           VARCHAR(200)   NOT NULL,
    starts_at       TIMESTAMPTZ    NOT NULL,
    capacity        INTEGER        NOT NULL,
    available_seats INTEGER        NOT NULL,
    price_cents     BIGINT         NOT NULL,
    status          VARCHAR(20)    NOT NULL,
    created_by      VARCHAR(255)   NOT NULL,
    version         BIGINT         NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_events_capacity_positive   CHECK (capacity > 0),
    CONSTRAINT ck_events_seats_not_negative  CHECK (available_seats >= 0),
    CONSTRAINT ck_events_seats_within_cap    CHECK (available_seats <= capacity),
    CONSTRAINT ck_events_price_not_negative  CHECK (price_cents >= 0)
);

CREATE INDEX idx_events_status_starts_at ON events (status, starts_at);
CREATE INDEX idx_events_created_by ON events (created_by);