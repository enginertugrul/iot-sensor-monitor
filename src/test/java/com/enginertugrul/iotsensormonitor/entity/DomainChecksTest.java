package com.enginertugrul.iotsensormonitor.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class DomainChecksTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ","\t","\n","\r\n"," \t\n ","\u2003"})
    void rejectsMissingOrBlankText(String value) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DomainChecks.requireText(value,"sensorName"))
                .withMessage("sensorName must not be blank");
    }

    @Test
    void trimsSurroundingWhitespaceWithoutChangingCaseOrInternalSpacing() {
        assertThat(DomainChecks.requireText(" \tLiving Room Sensor\r\n ","sensorName"))
                .isEqualTo("Living Room Sensor");
        assertThat(DomainChecks.requireText(" Sensor  A ","sensorName")).isEqualTo("Sensor  A");
    }

    @ParameterizedTest
    @ValueSource(doubles = {-Double.MAX_VALUE,-12.5,-0.0,0.0,Double.MIN_VALUE,12.5,Double.MAX_VALUE})
    void returnsFiniteNumbersUnchanged(double value) {
        assertThat(DomainChecks.requireFiniteDouble(value,"numericValue")).isEqualTo(value);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(doubles = {Double.NaN,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY})
    void rejectsMissingOrNonFiniteNumbers(Double value) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DomainChecks.requireFiniteDouble(value,"numericValue"))
                .withMessage("numericValue must be a finite number");
    }

    @ParameterizedTest
    @ValueSource(ints = {0,1,30,59,60})
    void acceptsIntegersWithinInclusiveBounds(int value) {
        assertThat(DomainChecks.requireIntegerBetween(value,0,60,"cooldownMinutes")).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE,-1,61,Integer.MAX_VALUE})
    void rejectsIntegersOutsideInclusiveBounds(int value) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DomainChecks.requireIntegerBetween(value,0,60,"cooldownMinutes"))
                .withMessage("cooldownMinutes must be between 0 and 60");
    }

    @Test
    void rejectsMissingInteger() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DomainChecks.requireIntegerBetween(null,0,60,"cooldownMinutes"))
                .withMessage("cooldownMinutes must not be null");
    }

    @Test
    void acceptsTheOnlyValueInASingleValueRange() {
        assertThat(DomainChecks.requireIntegerBetween(7,7,7,"value")).isEqualTo(7);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> DomainChecks.requireIntegerBetween(6,7,7,"value"))
                .withMessage("value must be between 7 and 7");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> DomainChecks.requireIntegerBetween(8,7,7,"value"))
                .withMessage("value must be between 7 and 7");
    }

    @Test
    void acceptsBothEndsOfTheFullIntegerRange() {
        assertThat(DomainChecks.requireIntegerBetween(Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE,"value"))
                .isEqualTo(Integer.MIN_VALUE);
        assertThat(DomainChecks.requireIntegerBetween(Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE,"value"))
                .isEqualTo(Integer.MAX_VALUE);
    }
}