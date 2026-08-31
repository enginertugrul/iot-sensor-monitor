package com.enginertugrul.iotsensormonitor.service.reading.statistics;

import com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsDisplayGranularity;
import com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsResolution;
import com.enginertugrul.iotsensormonitor.exception.InvalidStatisticsQueryException;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;




@Component
public class StatisticsResolutionPolicy {


    private final StatisticsQueryPolicy queryPolicy;



    public StatisticsResolutionPolicy(StatisticsQueryPolicy queryPolicy) {
        this.queryPolicy = queryPolicy;
    }



    public boolean shouldTryRawAutomatically(Duration effectiveRange) {
        Duration requiredRange = Objects.requireNonNull(effectiveRange,"effectiveRange must not be null");
        return requiredRange.compareTo(queryPolicy.getAutoRawMaximumRange()) <= 0;
    }



    public boolean fitsPointBudget(long pointCount) {
        return pointCount >= 0 && pointCount <= queryPolicy.getChartPointBudget();
    }



    public void requirePointBudget(StatisticsResolution resolution,long pointCount) {
        if (!fitsPointBudget(pointCount)) {
            throw new InvalidStatisticsQueryException(
                    "The requested " + resolution + " resolution exceeds the chart point budget of "
                            + queryPolicy.getChartPointBudget());
        }
    }



    public boolean fitsCsvExportRowLimit(long rowCount) {
        return rowCount <= queryPolicy.getCsvExportRowLimit();
    }

    public void requireCsvExportRowLimit(StatisticsResolution resolution,long rowCount) {
        if (!fitsCsvExportRowLimit(rowCount)) {
            throw new InvalidStatisticsQueryException(
                    "The requested " + resolution
                            + " CSV export would contain " + rowCount
                            + " rows, exceeding the configured limit of "
                            + queryPolicy.getCsvExportRowLimit());
        }
    }



    public StatisticsDisplayGranularity resolveDisplayGranularity(
            StatisticsResolution resolution,
            LocalDate firstLocalDate,
            LocalDate lastLocalDate
    ) {
        return switch (Objects.requireNonNull(resolution,"resolution must not be null")) {
            case RAW -> StatisticsDisplayGranularity.RAW;
            case HOURLY -> StatisticsDisplayGranularity.HOURLY;
            case DAILY -> resolveDailyDisplayGranularity(firstLocalDate,lastLocalDate);
            case AUTO -> throw new IllegalArgumentException("AUTO is not a resolved statistics resolution");
        };
    }



    private StatisticsDisplayGranularity resolveDailyDisplayGranularity(LocalDate firstLocalDate,LocalDate lastLocalDate) {

        LocalDate first = Objects.requireNonNull(firstLocalDate,"firstLocalDate must not be null");
        LocalDate last = Objects.requireNonNull(lastLocalDate,"lastLocalDate must not be null");

        if (first.isAfter(last)) {
            throw new IllegalArgumentException("firstLocalDate must not be after lastLocalDate");
        }

        long dailyPointCount = ChronoUnit.DAYS.between(first,last) + 1;

        if (fitsPointBudget(dailyPointCount)) {
            return StatisticsDisplayGranularity.DAILY;
        }

        LocalDate firstWeek = first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate lastWeek = last.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        long weeklyPointCount = ChronoUnit.DAYS.between(firstWeek,lastWeek) / 7 + 1;

        if (fitsPointBudget(weeklyPointCount)) {
            return StatisticsDisplayGranularity.WEEKLY;
        }

        YearMonth firstMonth = YearMonth.from(first);
        YearMonth lastMonth = YearMonth.from(last);
        long monthlyPointCount = (lastMonth.getYear() - firstMonth.getYear()) * 12L
                + lastMonth.getMonthValue() - firstMonth.getMonthValue() + 1;

        if (fitsPointBudget(monthlyPointCount)) {
            return StatisticsDisplayGranularity.MONTHLY;
        }

        throw new InvalidStatisticsQueryException(
                "The requested range cannot be displayed within the chart point budget of "
                        + queryPolicy.getChartPointBudget());
    }
}