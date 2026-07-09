ALTER TABLE reserved_dates ADD CONSTRAINT uq_accommodation_date UNIQUE (accommodation_id, reserved_at);
