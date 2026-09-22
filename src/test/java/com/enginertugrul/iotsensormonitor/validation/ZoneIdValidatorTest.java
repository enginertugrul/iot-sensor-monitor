package com.enginertugrul.iotsensormonitor.validation;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ZoneIdValidatorTest {

    private final ZoneIdValidator validator = new ZoneIdValidator();



    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ","\t\r\n","\u2003"})
    void leavesMissingTimezonesToRequiredFieldConstraints(String value) {
        assertThat(validator.isValid(value,null)).isTrue();
    }



    @ParameterizedTest
    @ValueSource(strings = {
            "UTC",
            "Europe/Istanbul",
            "America/New_York",
            "Asia/Kathmandu",
            "Z",
            "+03:00",
            "-05:30",
            "UTC+03:00"
    })
    void acceptsRegionIdsAndSupportedOffsetIds(String value) {
        assertThat(validator.isValid(value,null)).isTrue();
    }



    @ParameterizedTest
    @ValueSource(strings = {
            "Europe/Not_A_Zone",
            "europe/istanbul",
            "utc",
            " UTC",
            "UTC ",
            "+19:00",
            "GMT+25:00"
    })
    void rejectsUnknownMalformedOrUntrimmedTimezoneIds(String value) {
        assertThat(validator.isValid(value,null)).isFalse();
    }
}