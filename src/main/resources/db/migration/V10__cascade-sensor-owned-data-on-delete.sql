ALTER TABLE sensor_readings
    DROP CONSTRAINT fk_sensor_readings_sensor,
    ADD CONSTRAINT fk_sensor_readings_sensor
        FOREIGN KEY (sensor_id)
            REFERENCES sensors (id)
            ON DELETE CASCADE;

ALTER TABLE alert_rules
    DROP CONSTRAINT fk_alert_rules_sensor,
    ADD CONSTRAINT fk_alert_rules_sensor
        FOREIGN KEY (sensor_id)
            REFERENCES sensors (id)
            ON DELETE CASCADE;