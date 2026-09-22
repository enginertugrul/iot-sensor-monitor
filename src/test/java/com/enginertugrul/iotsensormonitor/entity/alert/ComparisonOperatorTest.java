package com.enginertugrul.iotsensormonitor.entity.alert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ComparisonOperatorTest {



    @ParameterizedTest
    @ValueSource(doubles = {-40.0,0.0,25.0})
    void aboveRequiresAValueStrictlyGreaterThanThreshold(double threshold) {
        assertThat(ComparisonOperator.ABOVE.matches(Math.nextDown(threshold),threshold)).isFalse();
        assertThat(ComparisonOperator.ABOVE.matches(threshold,threshold)).isFalse();
        assertThat(ComparisonOperator.ABOVE.matches(Math.nextUp(threshold),threshold)).isTrue();
    }



    @ParameterizedTest
    @ValueSource(doubles = {-40.0,0.0,25.0})
    void belowRequiresAValueStrictlyLessThanThreshold(double threshold) {
        assertThat(ComparisonOperator.BELOW.matches(Math.nextDown(threshold),threshold)).isTrue();
        assertThat(ComparisonOperator.BELOW.matches(threshold,threshold)).isFalse();
        assertThat(ComparisonOperator.BELOW.matches(Math.nextUp(threshold),threshold)).isFalse();
    }



    @ParameterizedTest
    @EnumSource(ComparisonOperator.class)
    void missingValueOrThresholdNeverMatches(ComparisonOperator operator) {
        assertThat(operator.matches(null,25.0)).isFalse();
        assertThat(operator.matches(25.0,null)).isFalse();
        assertThat(operator.matches(null,null)).isFalse();
    }



    @ParameterizedTest
    @EnumSource(ComparisonOperator.class)
    void nanValueOrThresholdNeverMatches(ComparisonOperator operator) {
        assertThat(operator.matches(Double.NaN,25.0)).isFalse();
        assertThat(operator.matches(25.0,Double.NaN)).isFalse();
        assertThat(operator.matches(Double.NaN,Double.NaN)).isFalse();
    }



    @Test
    void signedZerosAreEqualForBothOperators() {
        assertThat(ComparisonOperator.ABOVE.matches(-0.0,0.0)).isFalse();
        assertThat(ComparisonOperator.ABOVE.matches(0.0,-0.0)).isFalse();
        assertThat(ComparisonOperator.BELOW.matches(-0.0,0.0)).isFalse();
        assertThat(ComparisonOperator.BELOW.matches(0.0,-0.0)).isFalse();
    }
}