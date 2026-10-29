package com.enginertugrul.iotsensormonitor.validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class MaxUtf8BytesValidatorTest {

    private final MaxUtf8BytesValidator validator = new MaxUtf8BytesValidator();



    @BeforeEach
    void initializeWithTheBcryptLimit() throws NoSuchFieldException {
        validator.initialize(ConstrainedValue.class.getDeclaredField("value").getAnnotation(MaxUtf8Bytes.class));
    }



    @Test
    void leavesMissingValuesToRequiredFieldConstraints() {
        assertThat(validator.isValid(null,null)).isTrue();
    }



    @ParameterizedTest
    @MethodSource("valuesAtTheLimit")
    void acceptsValuesUpToTheByteLimit(String value) {
        assertThat(validator.isValid(value,null)).isTrue();
    }



    @ParameterizedTest
    @MethodSource("valuesOverTheLimit")
    void rejectsValuesOverTheByteLimit(String value) {
        assertThat(validator.isValid(value,null)).isFalse();
    }



    private static Stream<String> valuesAtTheLimit() {
        return Stream.of("a".repeat(72),"ş".repeat(36),"😀".repeat(18));
    }



    private static Stream<String> valuesOverTheLimit() {
        return Stream.of("a".repeat(73),"ş".repeat(37),"😀".repeat(19));
    }



    private static final class ConstrainedValue {

        @MaxUtf8Bytes(72)
        private String value;
    }
}