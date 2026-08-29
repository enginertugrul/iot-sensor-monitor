package com.enginertugrul.iotsensormonitor.service.reading.statistics.export;

import java.util.Objects;
import java.util.regex.Pattern;

public record StatisticsCsvExport(
        String fileName,
        String content
) {

    private static final Pattern SAFE_FILE_NAME =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*\\.csv");

    public StatisticsCsvExport {
        if (fileName == null
                || !SAFE_FILE_NAME.matcher(fileName).matches()) {

            throw new IllegalArgumentException("CSV fileName must be an ASCII-safe .csv filename");
        }

        Objects.requireNonNull(content,"content must not be null");

        if (content.isEmpty()) {
            throw new IllegalArgumentException("CSV content must not be empty");
        }
    }
}