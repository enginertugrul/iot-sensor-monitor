package com.enginertugrul.iottemperaturemonitor.entity;

public final class DomainChecks {

    private DomainChecks() {}

    public static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }

        return value.trim();
    }


    public static Double requireFiniteDouble(Double value, String fieldName) {

        if (value == null || value.isNaN() || value.isInfinite()) {
            throw new IllegalArgumentException(fieldName + " must be a finite number");
        }

        return value;
    }


    public static Integer requireIntegerBetween(Integer value,
                                                int minInclusive,
                                                int maxInclusive,
                                                String fieldName) {


        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }

        if (value < minInclusive || value > maxInclusive) {
            throw new IllegalArgumentException(
                    fieldName + " must be between " + minInclusive + " and " + maxInclusive
            );
        }

        return value;

    }


}
