package com.enginertugrul.iotsensormonitor.service.reading.statistics;

import com.enginertugrul.iotsensormonitor.dto.statistics.*;
import com.enginertugrul.iotsensormonitor.entity.measurement.SensorMeasurementPolicy;
import com.enginertugrul.iotsensormonitor.entity.reading.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorSummaryAggregate;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.support.temperature.TemperatureUnitConverter;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;




@Component
public class StatisticsResponseMapper {

    private static final MathContext AVERAGE_MATH_CONTEXT = MathContext.DECIMAL128;
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final TemperatureUnitConverter temperatureUnitConverter;


    public StatisticsResponseMapper(TemperatureUnitConverter temperatureUnitConverter) {
        this.temperatureUnitConverter = temperatureUnitConverter;
    }




    StatisticsSensorDTO toSensorDTO(Sensor sensor,TemperatureUnit temperatureUnit) {
        SensorType sensorType = sensor.getType();

        MeasurementUnit canonicalUnit = SensorMeasurementPolicy.supportsNumericMeasurements(sensorType)
                ? SensorMeasurementPolicy.requireCanonicalUnit(sensorType)
                : null;

        String displayUnit = null;
        String displayUnitSymbol = null;

        switch (sensorType) {
            case TEMPERATURE -> {
                displayUnit = temperatureUnit.name();
                displayUnitSymbol = temperatureUnitConverter.getSymbol(temperatureUnit);
            }
            case HUMIDITY -> {
                displayUnit = canonicalUnit.name();
                displayUnitSymbol = "% RH";
            }
            case MOTION -> {
            }
        }

        return new StatisticsSensorDTO(sensor.getId(),sensor.getName(),sensorType,sensor.getTimezone(),canonicalUnit,displayUnit,displayUnitSymbol);
    }




    StatisticsSeriesPointDTO toPointDTO(SensorType sensorType,StatisticsDataPoint point, StatisticsDisplayGranularity displayGranularity, TemperatureUnit temperatureUnit) {
        SensorSummaryAggregate aggregate = point.aggregate();

        StatisticsNumericMetricsDTO numericMetrics = null;
        StatisticsMotionMetricsDTO motionMetrics = null;
        Long sourceSampleCount = null;

        if (aggregate != null) {
            sourceSampleCount = aggregate.getSourceSampleCount();

            if (SensorMeasurementPolicy.supportsNumericMeasurements(sensorType)) {
                numericMetrics = toNumericMetrics(sensorType,aggregate,temperatureUnit);
            } else {
                motionMetrics = toMotionMetrics(aggregate);
            }
        }

        if (point instanceof RawStatisticsDataPoint rawPoint) {
            return new StatisticsSeriesPointDTO(
                    StatisticsDisplayGranularity.RAW,
                    rawPoint.sourceReadingId(),
                    rawPoint.recordedAt(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    rawPoint.status(),
                    sourceSampleCount,
                    numericMetrics,
                    motionMetrics,
                    null,
                    null);
        }

        IntervalStatisticsDataPoint intervalPoint = (IntervalStatisticsDataPoint) point;

        return new StatisticsSeriesPointDTO(
                displayGranularity,
                null,
                null,
                intervalPoint.interval().startInclusive(),
                intervalPoint.interval().endExclusive(),
                intervalPoint.localDateStart(),
                intervalPoint.localDateEndExclusive(),
                intervalPoint.timeZoneId(),
                intervalPoint.status(),
                sourceSampleCount,
                numericMetrics,
                motionMetrics,
                intervalPoint.finalizedAt(),
                intervalPoint.refreshedAt());
    }




    StatisticsPeriodMetricsDTO toPeriodMetricsDTO(SensorType sensorType,SensorSummaryAggregate aggregate,TemperatureUnit temperatureUnit) {
        if (aggregate == null) {
            return new StatisticsPeriodMetricsDTO(false,0,null,null);
        }

        StatisticsNumericMetricsDTO numericMetrics = null;
        StatisticsMotionMetricsDTO motionMetrics = null;

        if (SensorMeasurementPolicy.supportsNumericMeasurements(sensorType)) {
            numericMetrics = toNumericMetrics(sensorType,aggregate,temperatureUnit);
        } else {
            motionMetrics = toMotionMetrics(aggregate);
        }

        return new StatisticsPeriodMetricsDTO(true,aggregate.getSourceSampleCount(),numericMetrics,motionMetrics);
    }






    StatisticsCsvExportAvailabilityDTO toCsvExportAvailabilityDTO(StatisticsMaterializedSeries materialized,int rowLimit) {
        StatisticsResolution resolution = materialized.resolvedResolution();
        boolean summaryResolution = resolution == StatisticsResolution.HOURLY || resolution == StatisticsResolution.DAILY;
        int rowCount = summaryResolution ? materialized.sourcePoints().size() : 0;

        return new StatisticsCsvExportAvailabilityDTO(summaryResolution && rowCount <= rowLimit,rowCount,rowLimit);
    }




    StatisticsCoverageDTO toCoverageDTO(StatisticsAvailabilitySnapshot availability) {
        return new StatisticsCoverageDTO(toTierCoverageDTO(availability.raw()),toTierCoverageDTO(availability.hourly()),toTierCoverageDTO(availability.daily()));
    }




    private StatisticsTierCoverageDTO toTierCoverageDTO(StatisticsTierAvailability availability) {
        Instant representedFrom = availability.representedCoverage()
                .map(InstantRange::startInclusive)
                .orElse(null);

        Instant representedUntil = availability.representedCoverage()
                .map(InstantRange::endExclusive)
                .orElse(null);

        StatisticsRollupProgressDTO rollupProgressDTO = availability.rollupProgress()
                .map(this::toRollupProgressDTO)
                .orElse(null);

        return new StatisticsTierCoverageDTO(
                availability.resolution(),
                availability.retention().retentionWindow().startInclusive(),
                representedFrom,
                representedUntil,
                rollupProgressDTO);
    }




    private StatisticsRollupProgressDTO toRollupProgressDTO(StatisticsRollupProgress progress) {
        Instant verifiedFrom = progress.verifiedCoverage()
                .map(InstantRange::startInclusive)
                .orElse(null);

        Instant safeThrough = progress.verifiedCoverage()
                .map(InstantRange::endExclusive)
                .orElse(null);

        long lagSeconds = progress.lag().getSeconds();

        return new StatisticsRollupProgressDTO(verifiedFrom,safeThrough,progress.rollupDueUntilExclusive(),lagSeconds,lagSeconds > 0);
    }





    private StatisticsNumericMetricsDTO toNumericMetrics(SensorType sensorType,SensorSummaryAggregate aggregate,TemperatureUnit temperatureUnit) {
        if (!SensorMeasurementPolicy.supportsNumericMeasurements(sensorType)) {
            throw new IllegalArgumentException("Numeric metrics require a numeric sensor");
        }

        aggregate.requireCompatibleWith(sensorType);

        if (aggregate.getSourceSampleCount() == 0) {
            return null;
        }

        BigDecimal displaySum;
        BigDecimal displayMinimum;
        BigDecimal displayMaximum;

        if (sensorType == SensorType.TEMPERATURE) {
            displaySum = temperatureUnitConverter.convertSumFromCelsius(aggregate.getNumericSum(),aggregate.getSourceSampleCount(),temperatureUnit);
            displayMinimum = temperatureUnitConverter.convertDecimalFromCelsius(BigDecimal.valueOf(aggregate.getNumericMinimum()),temperatureUnit);
            displayMaximum = temperatureUnitConverter.convertDecimalFromCelsius(BigDecimal.valueOf(aggregate.getNumericMaximum()),temperatureUnit);
        } else {
            displaySum = aggregate.getNumericSum();
            displayMinimum = BigDecimal.valueOf(aggregate.getNumericMinimum());
            displayMaximum = BigDecimal.valueOf(aggregate.getNumericMaximum());
        }

        BigDecimal displayAverage = displaySum.divide(BigDecimal.valueOf(aggregate.getSourceSampleCount()),AVERAGE_MATH_CONTEXT);

        return new StatisticsNumericMetricsDTO(displaySum,displayMinimum,displayAverage,displayMaximum);
    }






    private StatisticsMotionMetricsDTO toMotionMetrics(SensorSummaryAggregate aggregate) {
        if (!aggregate.isBoolean()) {
            throw new IllegalArgumentException("Motion metrics require a boolean aggregate");
        }

        long totalSampleCount = aggregate.getSourceSampleCount();
        long trueSampleCount = aggregate.getTrueSampleCount();
        long falseSampleCount = totalSampleCount - trueSampleCount;

        BigDecimal truePercentage = totalSampleCount == 0
                ? null
                : BigDecimal.valueOf(trueSampleCount).multiply(ONE_HUNDRED)
                .divide(BigDecimal.valueOf(totalSampleCount),AVERAGE_MATH_CONTEXT);

        return new StatisticsMotionMetricsDTO(totalSampleCount,trueSampleCount,falseSampleCount,truePercentage);
    }


}