package com.enginertugrul.iotsensormonitor.service.reading.statistics;

import com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsMotionMetricsDTO;
import com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsNumericMetricsDTO;
import com.enginertugrul.iotsensormonitor.entity.measurement.SensorMeasurementPolicy;
import com.enginertugrul.iotsensormonitor.entity.reading.SensorReading;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorSummaryAggregate;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.repository.RawSensorReadingAggregateProjection;
import com.enginertugrul.iotsensormonitor.service.reading.SensorSummaryAggregator;
import com.enginertugrul.iotsensormonitor.support.temperature.TemperatureUnitConverter;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;




@Component
public class StatisticsAggregationPolicy {


    private static final MathContext AVERAGE_MATH_CONTEXT = MathContext.DECIMAL128;
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final TemperatureUnitConverter temperatureUnitConverter;



    public StatisticsAggregationPolicy(TemperatureUnitConverter temperatureUnitConverter) {
        this.temperatureUnitConverter = temperatureUnitConverter;
    }





    public SensorSummaryAggregate empty(SensorType sensorType) {
        return SensorSummaryAggregator.empty(sensorType);
    }



    public SensorSummaryAggregate fromReading(SensorType sensorType,SensorReading reading) {
        return SensorSummaryAggregator.fromReading(sensorType,reading);
    }



    public SensorSummaryAggregate fromRawReadings(SensorType sensorType, RawSensorReadingAggregateProjection rawAggregate) {
        return SensorSummaryAggregator.fromRawReadings(sensorType,rawAggregate);
    }



    public SensorSummaryAggregate combine(
            SensorType sensorType,
            Iterable<SensorSummaryAggregate> aggregates
    ) {
        return SensorSummaryAggregator.combine(sensorType,aggregates);
    }




    public StatisticsNumericMetricsDTO toNumericMetrics(
            SensorType sensorType,
            SensorSummaryAggregate aggregate,
            TemperatureUnit temperatureUnit
    ) {
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
            displaySum = temperatureUnitConverter.convertSumFromCelsius(
                    aggregate.getNumericSum(),
                    aggregate.getSourceSampleCount(),
                    temperatureUnit);
            displayMinimum = temperatureUnitConverter.convertDecimalFromCelsius(
                    BigDecimal.valueOf(aggregate.getNumericMinimum()),
                    temperatureUnit);
            displayMaximum = temperatureUnitConverter.convertDecimalFromCelsius(
                    BigDecimal.valueOf(aggregate.getNumericMaximum()),
                    temperatureUnit);
        } else {
            displaySum = aggregate.getNumericSum();
            displayMinimum = BigDecimal.valueOf(aggregate.getNumericMinimum());
            displayMaximum = BigDecimal.valueOf(aggregate.getNumericMaximum());
        }

        BigDecimal displayAverage = displaySum.divide(
                BigDecimal.valueOf(aggregate.getSourceSampleCount()),
                AVERAGE_MATH_CONTEXT);

        return new StatisticsNumericMetricsDTO(
                displaySum,
                displayMinimum,
                displayAverage,
                displayMaximum);
    }




    public StatisticsMotionMetricsDTO toMotionMetrics(SensorSummaryAggregate aggregate) {
        if (!aggregate.isBoolean()) {
            throw new IllegalArgumentException("Motion metrics require a boolean aggregate");
        }

        long totalSampleCount = aggregate.getSourceSampleCount();
        long trueSampleCount = aggregate.getTrueSampleCount();
        long falseSampleCount = totalSampleCount - trueSampleCount;

        BigDecimal truePercentage = totalSampleCount == 0
                ? null
                : BigDecimal.valueOf(trueSampleCount)
                .multiply(ONE_HUNDRED)
                .divide(BigDecimal.valueOf(totalSampleCount),AVERAGE_MATH_CONTEXT);

        return new StatisticsMotionMetricsDTO(
                totalSampleCount,
                trueSampleCount,
                falseSampleCount,
                truePercentage);
    }

}