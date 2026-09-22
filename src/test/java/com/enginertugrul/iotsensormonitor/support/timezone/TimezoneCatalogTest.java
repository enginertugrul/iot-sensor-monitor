package com.enginertugrul.iotsensormonitor.support.timezone;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimezoneCatalogTest {

    private static final Instant WINTER_INSTANT = Instant.parse("2024-01-15T12:00:00Z");

    private final TimezoneCatalog catalog = new TimezoneCatalog(Clock.fixed(WINTER_INSTANT,ZoneOffset.UTC));

    @ParameterizedTest
    @CsvSource({
            "2024-01-15T12:00:00Z,UTC,UTC+00:00 - UTC",
            "2024-01-15T12:00:00Z,Asia/Kolkata,UTC+05:30 - Asia/Kolkata",
            "2024-01-15T12:00:00Z,Asia/Kathmandu,UTC+05:45 - Asia/Kathmandu",
            "2024-01-15T12:00:00Z,America/St_Johns,UTC-03:30 - America/St_Johns",
            "2024-01-15T12:00:00Z,America/New_York,UTC-05:00 - America/New_York",
            "2024-07-15T12:00:00Z,America/New_York,UTC-04:00 - America/New_York",
            "2024-01-15T12:00:00Z,Europe/Berlin,UTC+01:00 - Europe/Berlin",
            "2024-07-15T12:00:00Z,Europe/Berlin,UTC+02:00 - Europe/Berlin",
            "2024-03-31T00:59:59Z,Europe/Berlin,UTC+01:00 - Europe/Berlin",
            "2024-03-31T01:00:00Z,Europe/Berlin,UTC+02:00 - Europe/Berlin",
            "2024-10-27T00:59:59Z,Europe/Berlin,UTC+02:00 - Europe/Berlin",
            "2024-10-27T01:00:00Z,Europe/Berlin,UTC+01:00 - Europe/Berlin",
            "2024-01-15T12:00:00Z,+05:45,UTC+05:45 - +05:45"
    })
    void formatsLabelsUsingTheOffsetAtTheClockInstant(String instant,String zoneId,String expected) {
        Clock clock = Clock.fixed(Instant.parse(instant),ZoneOffset.UTC);
        TimezoneCatalog fixedCatalog = new TimezoneCatalog(clock);

        assertThat(fixedCatalog.toDisplayName(zoneId)).isEqualTo(expected);
    }

    @Test
    void usesTheRequestedTimezoneRegardlessOfTheClocksTimezone() {
        Clock clock = Clock.fixed(WINTER_INSTANT,ZoneId.of("Pacific/Auckland"));
        TimezoneCatalog fixedCatalog = new TimezoneCatalog(clock);

        assertThat(fixedCatalog.toDisplayName("America/New_York"))
                .isEqualTo("UTC-05:00 - America/New_York");
    }

    @Test
    void includesEveryAvailableTimezoneExactlyOnceInIdOrder() {
        List<TimezoneOptionDTO> options = catalog.getTimezoneOptions();
        List<String> expectedIds = ZoneId.getAvailableZoneIds().stream().sorted().toList();

        assertThat(options).extracting(TimezoneOptionDTO::id).containsExactlyElementsOf(expectedIds);
    }

    @Test
    void includesDisplayLabelsInTimezoneOptions() {
        List<TimezoneOptionDTO> options = catalog.getTimezoneOptions();

        assertThat(options).contains(
                new TimezoneOptionDTO("UTC","UTC+00:00 - UTC"),
                new TimezoneOptionDTO("Europe/Berlin","UTC+01:00 - Europe/Berlin"),
                new TimezoneOptionDTO("Asia/Kathmandu","UTC+05:45 - Asia/Kathmandu"),
                new TimezoneOptionDTO("America/St_Johns","UTC-03:30 - America/St_Johns")
        );
    }

    @Test
    void usesSummerOffsetsWhenBuildingTimezoneOptionsInSummer() {
        Clock clock = Clock.fixed(Instant.parse("2024-07-15T12:00:00Z"),ZoneOffset.UTC);
        TimezoneCatalog summerCatalog = new TimezoneCatalog(clock);

        assertThat(summerCatalog.getTimezoneOptions()).contains(
                new TimezoneOptionDTO("Europe/Berlin","UTC+02:00 - Europe/Berlin"),
                new TimezoneOptionDTO("America/New_York","UTC-04:00 - America/New_York")
        );
    }

    @Test
    void returnsAnUnmodifiableOptionsList() {
        List<TimezoneOptionDTO> options = catalog.getTimezoneOptions();

        assertThatThrownBy(() -> options.add(new TimezoneOptionDTO("Custom","Custom")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {""," ","Mars/Olympus_Mons","+19:00"})
    void rejectsInvalidTimezoneIds(String zoneId) {
        assertThatThrownBy(() -> catalog.toDisplayName(zoneId)).isInstanceOf(DateTimeException.class);
    }

    @Test
    void rejectsNullTimezoneId() {
        assertThatNullPointerException().isThrownBy(() -> catalog.toDisplayName(null));
    }
}