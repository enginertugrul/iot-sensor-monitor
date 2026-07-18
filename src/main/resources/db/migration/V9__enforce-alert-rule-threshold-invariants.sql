ALTER TABLE alert_rules
    ADD CONSTRAINT ck_alert_rules_threshold_unit CHECK (
        threshold_unit IS NULL
            OR threshold_unit IN ('C', 'PERCENT')
        ),

    ADD CONSTRAINT ck_alert_rules_threshold_finite CHECK (
        threshold_value IS NULL
            OR threshold_value NOT IN (
                                       'NaN'::DOUBLE PRECISION,
                                       'Infinity'::DOUBLE PRECISION,
                                       '-Infinity'::DOUBLE PRECISION
            )
        ),

    ADD CONSTRAINT ck_alert_rules_temperature_range CHECK (
        threshold_unit IS DISTINCT FROM 'C'
            OR threshold_value >= -273.15
        ),

    ADD CONSTRAINT ck_alert_rules_humidity_range CHECK (
        threshold_unit IS DISTINCT FROM 'PERCENT'
            OR threshold_value BETWEEN 0.0 AND 100.0
        );