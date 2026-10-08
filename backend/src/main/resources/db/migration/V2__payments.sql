CREATE TABLE payments (
  id UUID PRIMARY KEY,
  reservation_id UUID NOT NULL UNIQUE REFERENCES reservations(id),
  idempotency_key VARCHAR(128) NOT NULL UNIQUE,
  status VARCHAR(16) NOT NULL CHECK (status IN ('SUCCESS','FAILED','TIMEOUT')),
  amount_cents INTEGER NOT NULL CHECK (amount_cents >= 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_payments_created_at ON payments(created_at);
