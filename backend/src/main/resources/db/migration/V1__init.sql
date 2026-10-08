CREATE TABLE shows (
  id UUID PRIMARY KEY,
  title VARCHAR(180) NOT NULL,
  venue VARCHAR(180) NOT NULL,
  starts_at TIMESTAMPTZ NOT NULL,
  price_cents INTEGER NOT NULL CHECK (price_cents >= 0)
);
CREATE TABLE show_seats (
  id UUID PRIMARY KEY,
  show_id UUID NOT NULL REFERENCES shows(id),
  label VARCHAR(12) NOT NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE' CHECK (state IN ('AVAILABLE','RESERVED','BOOKED')),
  UNIQUE(show_id, label),
  UNIQUE(id, show_id)
);
CREATE INDEX idx_show_seats_show_state ON show_seats(show_id, state);
CREATE TABLE reservations (
  id UUID PRIMARY KEY,
  show_id UUID NOT NULL REFERENCES shows(id),
  status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','CONFIRMED','RELEASED','EXPIRED')),
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE reservation_seats (
  reservation_id UUID NOT NULL REFERENCES reservations(id),
  show_id UUID NOT NULL,
  seat_id UUID NOT NULL,
  PRIMARY KEY(reservation_id, seat_id),
  FOREIGN KEY(seat_id, show_id) REFERENCES show_seats(id, show_id)
);
