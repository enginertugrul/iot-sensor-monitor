ALTER TABLE alert_rules
    ADD COLUMN cooldown_minutes INTEGER NOT NULL DEFAULT 60,
    ADD COLUMN last_triggered_at TIMESTAMPTZ;

ALTER TABLE alert_rules
    ADD CONSTRAINT ck_alert_rules_cooldown_minutes
        CHECK (cooldown_minutes BETWEEN 1 AND 10080);