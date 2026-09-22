package com.enginertugrul.iotsensormonitor.support.temperature;

import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

class TemperatureUnitConverterTest {

    private final TemperatureUnitConverter converter = new TemperatureUnitConverter();

    @ParameterizedTest
    @MethodSource("knownTemperatures")
    void convertsFromCelsius(TemperatureUnit unit,double celsius,double converted) {
        assertThat(converter.convertFromCelsius(celsius,unit)).isCloseTo(converted,within(1.0e-9));
    }

    @ParameterizedTest
    @MethodSource("knownTemperatures")
    void convertsToCelsius(TemperatureUnit unit,double celsius,double converted) {
        assertThat(converter.convertToCelsius(converted,unit)).isCloseTo(celsius,within(1.0e-9));
    }

    @ParameterizedTest
    @CsvSource({
            "CELSIUS,20.1234567890123456789,20.1234567890123456789",
            "FAHRENHEIT,-40,-40",
            "FAHRENHEIT,0,32",
            "FAHRENHEIT,100,212",
            "FAHRENHEIT,0.0000000000000000001,32.00000000000000000018",
            "KELVIN,-273.15,0",
            "KELVIN,0,273.15",
            "KELVIN,100,373.15",
            "KELVIN,0.0000000000000000001,273.1500000000000000001"
    })
    void convertsDecimalsWithoutLosingPrecision(TemperatureUnit unit,String celsius,String expected) {
        BigDecimal result = converter.convertDecimalFromCelsius(new BigDecimal(celsius),unit);

        assertThat(result).isEqualByComparingTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "CELSIUS,60,3,60",
            "FAHRENHEIT,20,1,68",
            "FAHRENHEIT,60,3,204",
            "FAHRENHEIT,0,3,96",
            "FAHRENHEIT,-60,3,-12",
            "FAHRENHEIT,0.0000000000000000001,2,64.00000000000000000018",
            "FAHRENHEIT,0,3000000000,96000000000",
            "KELVIN,20,1,293.15",
            "KELVIN,60,3,879.45",
            "KELVIN,0,3,819.45",
            "KELVIN,-60,3,759.45",
            "KELVIN,0.0000000000000000001,2,546.3000000000000000001",
            "KELVIN,0,3000000000,819450000000"
    })
    void convertsAggregateSumsWithAnOffsetForEverySample(TemperatureUnit unit,String celsiusSum,long sampleCount,String expected) {
        BigDecimal result = converter.convertSumFromCelsius(new BigDecimal(celsiusSum),sampleCount,unit);

        assertThat(result).isEqualByComparingTo(expected);
    }

    @ParameterizedTest
    @EnumSource(TemperatureUnit.class)
    @NullSource
    void convertsAnEmptyAggregateToZero(TemperatureUnit unit) {
        assertThat(converter.convertSumFromCelsius(BigDecimal.ZERO,0,unit)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @ParameterizedTest
    @EnumSource(TemperatureUnit.class)
    @NullSource
    void preservesNullValues(TemperatureUnit unit) {
        assertThat(converter.convertFromCelsius(null,unit)).isNull();
        assertThat(converter.convertToCelsius(null,unit)).isNull();
        assertThat(converter.convertDecimalFromCelsius(null,unit)).isNull();
        assertThat(converter.convertSumFromCelsius(null,0,unit)).isNull();
        assertThat(converter.convertSumFromCelsius(null,3,unit)).isNull();
    }

    @ParameterizedTest
    @ValueSource(longs = {-1,Long.MIN_VALUE})
    void rejectsNegativeSampleCountsEvenWhenTheSumIsNull(long sampleCount) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> converter.convertSumFromCelsius(BigDecimal.TEN,sampleCount,TemperatureUnit.FAHRENHEIT))
                .withMessage("sampleCount must not be negative");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> converter.convertSumFromCelsius(null,sampleCount,TemperatureUnit.FAHRENHEIT))
                .withMessage("sampleCount must not be negative");
    }

    @Test
    void defaultsNullUnitsToCelsiusForEveryOperation() {
        BigDecimal decimal = new BigDecimal("20.1234567890123456789");

        assertThat(converter.convertFromCelsius(20.5,null)).isEqualTo(20.5);
        assertThat(converter.convertToCelsius(20.5,null)).isEqualTo(20.5);
        assertThat(converter.convertDecimalFromCelsius(decimal,null)).isEqualByComparingTo(decimal);
        assertThat(converter.convertSumFromCelsius(decimal,3,null)).isEqualByComparingTo(decimal);
        assertThat(converter.getSymbol(null)).isEqualTo("°C");
    }

    @ParameterizedTest
    @CsvSource({
            "CELSIUS,°C",
            "FAHRENHEIT,°F",
            "KELVIN,K"
    })
    void returnsTheRequestedUnitSymbol(TemperatureUnit unit,String expected) {
        assertThat(converter.getSymbol(unit)).isEqualTo(expected);
    }

    private static Stream<Arguments> knownTemperatures() {
        return Stream.of(
                Arguments.of(TemperatureUnit.CELSIUS,-273.15,-273.15),
                Arguments.of(TemperatureUnit.CELSIUS,-40.0,-40.0),
                Arguments.of(TemperatureUnit.CELSIUS,0.0,0.0),
                Arguments.of(TemperatureUnit.CELSIUS,100.0,100.0),
                Arguments.of(TemperatureUnit.FAHRENHEIT,-273.15,-459.67),
                Arguments.of(TemperatureUnit.FAHRENHEIT,-40.0,-40.0),
                Arguments.of(TemperatureUnit.FAHRENHEIT,0.0,32.0),
                Arguments.of(TemperatureUnit.FAHRENHEIT,100.0,212.0),
                Arguments.of(TemperatureUnit.KELVIN,-273.15,0.0),
                Arguments.of(TemperatureUnit.KELVIN,-40.0,233.15),
                Arguments.of(TemperatureUnit.KELVIN,0.0,273.15),
                Arguments.of(TemperatureUnit.KELVIN,100.0,373.15)
        );
    }
}