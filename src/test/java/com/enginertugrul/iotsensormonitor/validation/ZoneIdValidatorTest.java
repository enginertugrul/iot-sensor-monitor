package com.enginertugrul.iotsensormonitor.validation;

import com.enginertugrul.iotsensormonitor.support.timezone.TimezoneCatalog;
import com.enginertugrul.iotsensormonitor.support.timezone.TimezoneOptionDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

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
            "Etc/GMT-3"
    })
    void acceptsAvailableRegionIds(String value) {
        assertThat(validator.isValid(value,null)).isTrue();
    }



    @Test
    void acceptsEveryTimezoneOfferedByTheCatalog() {
        TimezoneCatalog catalog = new TimezoneCatalog(Clock.fixed(Instant.parse("2024-01-15T12:00:00Z"),ZoneOffset.UTC));

        assertThat(catalog.getTimezoneOptions())
                .extracting(TimezoneOptionDTO::id)
                .isNotEmpty()
                .allMatch(id -> validator.isValid(id,null));
    }



    @ParameterizedTest
    @ValueSource(strings = {
            "Z",
            "+03:00",
            "-05:30",
            "UTC+03:00",
            "GMT+03:00",
            "UT"
    })
    void rejectsOffsetIdsOutsideTheCatalog(String value) {
        assertThat(validator.isValid(value,null)).isFalse();
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