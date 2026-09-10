package com.enginertugrul.iotsensormonitor.support.timezone;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

@Component
public class TimezoneCatalog {

    private final Clock clock;

    public TimezoneCatalog(Clock clock) {
        this.clock = clock;
    }

    public List<TimezoneOptionDTO> getTimezoneOptions() {

        return ZoneId.getAvailableZoneIds()
                .stream()
                .sorted()
                .map(zoneId -> new TimezoneOptionDTO(zoneId, toDisplayName(zoneId)))
                .toList();
    }


    public String toDisplayName(String zoneId) {
        ZoneId parsedZoneId = ZoneId.of(zoneId);
        ZoneOffset offset = parsedZoneId.getRules().getOffset(clock.instant());
        String offsetText = "Z".equals(offset.getId()) ? "+00:00" : offset.getId();

        return "UTC" + offsetText + " - " + zoneId;
    }
}