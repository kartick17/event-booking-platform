CREATE TABLE bookings (
      id              BIGSERIAL PRIMARY KEY,
      booking_ref     VARCHAR(20)  NOT NULL,
      user_id         VARCHAR(255) NOT NULL,
      event_id        BIGINT       NOT NULL,
      event_title     VARCHAR(200) NOT NULL,
      quantity        INTEGER      NOT NULL,
      unit_price_cents BIGINT      NOT NULL,
      total_cents     BIGINT       NOT NULL,
      status          VARCHAR(20)  NOT NULL,
      version         BIGINT       NOT NULL DEFAULT 0,
      created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
      updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

      CONSTRAINT uk_bookings_ref UNIQUE (booking_ref),
      CONSTRAINT ck_bookings_quantity CHECK (quantity > 0 AND quantity <= 10),
      CONSTRAINT ck_bookings_total CHECK (total_cents >= 0)
);

CREATE INDEX idx_bookings_user_id ON bookings (user_id, created_at DESC);
CREATE INDEX idx_bookings_event_id ON bookings (event_id);
CREATE INDEX idx_bookings_status ON bookings (status);