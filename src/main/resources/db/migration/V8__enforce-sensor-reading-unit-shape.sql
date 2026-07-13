ALTER TABLE sensor_readings
    DROP CONSTRAINT IF EXISTS ck_sensor_readings_exactly_one_value,

    ADD CONSTRAINT ck_sensor_readings_value_shape CHECK (
        (
            numeric_value IS NOT NULL
                AND boolean_value IS NULL
                AND unit IS NOT NULL
            )
            OR
        (
            numeric_value IS NULL
                AND boolean_value IS NOT NULL
                AND unit IS NULL
            )
        ),

    ADD CONSTRAINT ck_sensor_readings_unit CHECK (
        unit IS NULL
            OR unit IN ('C', 'PERCENT')
        ),

    ADD CONSTRAINT ck_sensor_readings_numeric_finite CHECK (
        numeric_value IS NULL
            OR numeric_value NOT IN (
                                     'NaN'::DOUBLE PRECISION,
                                     'Infinity'::DOUBLE PRECISION,
                                     '-Infinity'::DOUBLE PRECISION
            )
        ),

    ADD CONSTRAINT ck_sensor_readings_temperature_range CHECK (
        unit IS DISTINCT FROM 'C'
            OR numeric_value >= -273.15
        ),

    ADD CONSTRAINT ck_sensor_readings_humidity_range CHECK (
        unit IS DISTINCT FROM 'PERCENT'
            OR numeric_value BETWEEN 0.0 AND 100.0
        );