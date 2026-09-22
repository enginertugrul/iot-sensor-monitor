package com.enginertugrul.iotsensormonitor.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class IsFiniteDoubleValidatorTest {

    private final IsFiniteDoubleValidator validator = new IsFiniteDoubleValidator();



    @Test
    void leavesMissingValuesToRequiredFieldConstraints() {
        assertThat(validator.isValid(null,null)).isTrue();
    }



    @ParameterizedTest
    @ValueSource(doubles = {-Double.MAX_VALUE,-12.5,-Double.MIN_VALUE,-0.0,0.0,Double.MIN_VALUE,12.5,Double.MAX_VALUE})
    void acceptsFiniteValues(double value) {
        assertThat(validator.isValid(value,null)).isTrue();
    }



    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY})
    void rejectsNonFiniteValues(double value) {
        assertThat(validator.isValid(value,null)).isFalse();
    }
}