package com.enginertugrul.iotsensormonitor.service.reading.statistics.export;

import com.enginertugrul.iotsensormonitor.dto.statistics.SensorStatisticsExportDTO;
import com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsMotionMetricsDTO;
import com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsNumericMetricsDTO;
import com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsResolution;
import com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsSensorDTO;
import com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsSeriesPointDTO;
import com.enginertugrul.iotsensormonitor.entity.measurement.SensorMeasurementPolicy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;


@Component
public class StatisticsCsvWriter {

    private static final String UTC_TIME_ZONE = "UTC";
    private static final String LINE_ENDING = "\r\n";

    private static final List<String> COMMON_HEADERS = List.of(
            "granularity",
            "sensor_id",
            "sensor_name",
            "sensor_type",
            "sensor_time_zone",
            "period_time_zone",
            "local_date",
            "period_start_utc",
            "period_end_utc",
            "status"
    );

    private static final List<String> NUMERIC_HEADERS = List.of(
            "sample_count",
            "canonical_storage_unit",
            "metric_unit",
            "metric_unit_symbol",
            "sum",
            "minimum",
            "average",
            "maximum"
    );

    private static final List<String> MOTION_HEADERS = List.of(
            "total_sample_count",
            "true_sample_count",
            "false_sample_count",
            "true_percentage"
    );

    private static final List<String> FINALIZATION_HEADERS = List.of(
            "finalized_at_utc",
            "refreshed_at_utc"
    );

    public String write(SensorStatisticsExportDTO export) {
        StatisticsSensorDTO sensor = export.sensor();

        boolean numericSensor =
                SensorMeasurementPolicy.supportsNumericMeasurements(
                        sensor.type());

        CsvDocumentWriter csv =
                new CsvDocumentWriter(initialCapacity(export.rows().size()));

        appendHeaders(csv,numericSensor);

        for (StatisticsSeriesPointDTO row : export.rows()) {
            appendRow(
                    csv,
                    sensor,
                    export.resolvedResolution(),
                    row,
                    numericSensor);
        }

        return csv.content();
    }

    private void appendHeaders(
            CsvDocumentWriter csv,
            boolean numericSensor
    ) {
        COMMON_HEADERS.forEach(csv::text);

        if (numericSensor) {
            NUMERIC_HEADERS.forEach(csv::text);
        } else {
            MOTION_HEADERS.forEach(csv::text);
        }

        FINALIZATION_HEADERS.forEach(csv::text);
        csv.endRow();
    }

    private void appendRow(
            CsvDocumentWriter csv,
            StatisticsSensorDTO sensor,
            StatisticsResolution resolution,
            StatisticsSeriesPointDTO point,
            boolean numericSensor
    ) {
        csv.text(point.granularity().name());
        csv.integer(sensor.id());
        csv.text(sensor.name());
        csv.text(sensor.type().name());
        csv.text(sensor.timeZoneId());
        csv.text(
                resolution == StatisticsResolution.HOURLY
                        ? UTC_TIME_ZONE
                        : point.timeZoneId());
        csv.text(
                point.localDateStart() == null
                        ? null
                        : point.localDateStart().toString());
        csv.text(point.bucketStart().toString());
        csv.text(point.bucketEnd().toString());
        csv.text(point.status().name());

        if (numericSensor) {
            appendNumericMetrics(csv,sensor,point);
        } else {
            appendMotionMetrics(csv,point);
        }

        csv.text(
                point.finalizedAt() == null
                        ? null
                        : point.finalizedAt().toString());
        csv.text(
                point.refreshedAt() == null
                        ? null
                        : point.refreshedAt().toString());
        csv.endRow();
    }

    private void appendNumericMetrics(
            CsvDocumentWriter csv,
            StatisticsSensorDTO sensor,
            StatisticsSeriesPointDTO point
    ) {
        StatisticsNumericMetricsDTO metrics =
                point.numericMetrics();

        csv.integer(point.sourceSampleCount());
        csv.text(sensor.canonicalUnit().name());
        csv.text(sensor.displayUnit());
        csv.text(sensor.displayUnitSymbol());

        if (metrics == null) {
            csv.blank();
            csv.blank();
            csv.blank();
            csv.blank();
            return;
        }

        csv.decimal(metrics.sum());
        csv.decimal(metrics.minimum());
        csv.decimal(metrics.average());
        csv.decimal(metrics.maximum());
    }

    private void appendMotionMetrics(
            CsvDocumentWriter csv,
            StatisticsSeriesPointDTO point
    ) {
        StatisticsMotionMetricsDTO metrics =
                point.motionMetrics();

        if (metrics == null) {
            csv.blank();
            csv.blank();
            csv.blank();
            csv.blank();
            return;
        }

        csv.integer(metrics.totalSampleCount());
        csv.integer(metrics.trueSampleCount());
        csv.integer(metrics.falseSampleCount());
        csv.decimal(metrics.truePercentage());
    }

    private int initialCapacity(int rowCount) {
        long estimatedCapacity =
                512L + (long) rowCount * 320L;

        return (int) Math.min(
                estimatedCapacity,
                1_000_000L);
    }

    private static String neutralizeSpreadsheetFormula(
            String value
    ) {
        String sanitized = value.replace('\0','\uFFFD');

        if (sanitized.isEmpty()) {
            return sanitized;
        }

        char firstCharacter = sanitized.charAt(0);

        if (firstCharacter == '\t'
                || firstCharacter == '\r'
                || firstCharacter == '\n') {

            return "'" + sanitized;
        }

        int firstNonWhitespace = 0;

        while (firstNonWhitespace < sanitized.length()) {
            char character = sanitized.charAt(firstNonWhitespace);

            if (!Character.isWhitespace(character)
                    && !Character.isSpaceChar(character)) {

                break;
            }

            firstNonWhitespace++;
        }

        if (firstNonWhitespace >= sanitized.length()) {
            return sanitized;
        }

        char formulaCandidate =
                sanitized.charAt(firstNonWhitespace);

        if (formulaCandidate == '='
                || formulaCandidate == '+'
                || formulaCandidate == '-'
                || formulaCandidate == '@') {

            return "'" + sanitized;
        }

        return sanitized;
    }

    private static final class CsvDocumentWriter {

        private final StringBuilder output;
        private boolean firstCell = true;

        private CsvDocumentWriter(int initialCapacity) {
            output = new StringBuilder(initialCapacity);
        }

        private void text(String value) {
            beginCell();

            if (value == null) {
                return;
            }

            appendEscapedText(
                    neutralizeSpreadsheetFormula(value));
        }

        private void integer(Long value) {
            beginCell();

            if (value != null) {
                output.append(value);
            }
        }

        private void decimal(BigDecimal value) {
            beginCell();

            if (value != null) {
                output.append(
                        value.stripTrailingZeros().toPlainString());
            }
        }

        private void blank() {
            beginCell();
        }

        private void endRow() {
            output.append(LINE_ENDING);
            firstCell = true;
        }

        private String content() {
            if (!firstCell) {
                throw new IllegalStateException(
                        "The final CSV row has not been terminated");
            }

            return output.toString();
        }

        private void beginCell() {
            if (!firstCell) {
                output.append(',');
            }

            firstCell = false;
        }

        private void appendEscapedText(String value) {
            boolean requiresQuotes =
                    value.indexOf(',') >= 0
                            || value.indexOf('"') >= 0
                            || value.indexOf('\r') >= 0
                            || value.indexOf('\n') >= 0;

            if (!requiresQuotes) {
                output.append(value);
                return;
            }

            output.append('"');

            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);

                if (character == '"') {
                    output.append("\"\"");
                } else {
                    output.append(character);
                }
            }

            output.append('"');
        }
    }
}