package com.enginertugrul.iottemperaturemonitor.entity;

public final class DomainChecks {

    private DomainChecks() {}

    public static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }

        return value.trim();
    }


}
