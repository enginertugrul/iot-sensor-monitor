ALTER TABLE sensors
    RENAME COLUMN home_location TO installation_location;

ALTER TABLE sensors
    RENAME CONSTRAINT ck_sensors_home_location_not_blank
        TO ck_sensors_installation_location_not_blank;