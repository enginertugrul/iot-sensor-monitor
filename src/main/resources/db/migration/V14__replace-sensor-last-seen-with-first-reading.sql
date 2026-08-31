ALTER TABLE sensors
    DROP COLUMN last_seen_at;

ALTER TABLE sensors
    ADD COLUMN first_reading_at TIMESTAMPTZ;