package com.enginertugrul.iotsensormonitor.entity.measurement;

import com.enginertugrul.iotsensormonitor.entity.DomainChecks;
import com.enginertugrul.iotsensormonitor.entity.reading.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;

import java.util.Map;
import java.util.Objects;




public final class SensorMeasurementPolicy {



    private static final Map<SensorType, NumericSpecification>
            NUMERIC_SPECIFICATIONS = Map.of(
            SensorType.TEMPERATURE,
            new NumericSpecification(MeasurementUnit.C,-273.15,Double.MAX_VALUE),
            SensorType.HUMIDITY,
            new NumericSpecification(MeasurementUnit.PERCENT,0.0,100.0)
    );



    private SensorMeasurementPolicy() {
    }



    public static boolean supportsNumericMeasurements(SensorType sensorType) {

        return NUMERIC_SPECIFICATIONS.containsKey(Objects.requireNonNull(sensorType,"sensorType must not be null"));
    }




    public static MeasurementUnit requireCanonicalUnit(SensorType sensorType) {
        return requireSpecification(sensorType).unit();
    }



    public static double requireValidNumericValue(SensorType sensorType,Double value,String fieldName) {

        NumericSpecification specification =requireSpecification(sensorType);

        double finiteValue = DomainChecks.requireFiniteDouble(value, fieldName);

        if (finiteValue < specification.minimumInclusive() || finiteValue > specification.maximumInclusive()) {
            throw new IllegalArgumentException( fieldName + " is outside the valid range for " + sensorType);
        }

        return finiteValue;
    }




    private static NumericSpecification requireSpecification(SensorType sensorType) {

        SensorType requiredSensorType =Objects.requireNonNull(sensorType,"sensorType must not be null");

        NumericSpecification specification = NUMERIC_SPECIFICATIONS.get(requiredSensorType);

        if (specification == null) {
            throw new IllegalArgumentException(requiredSensorType + " does not support numeric measurements");
        }

        return specification;
    }




    private record NumericSpecification(MeasurementUnit unit, double minimumInclusive, double maximumInclusive) {

        private NumericSpecification {
            Objects.requireNonNull(unit, "unit must not be null");
        }

    }



}