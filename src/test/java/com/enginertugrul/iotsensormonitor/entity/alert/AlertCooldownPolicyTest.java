package com.enginertugrul.iotsensormonitor.entity.alert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AlertCooldownPolicyTest {



    @ParameterizedTest
    @ValueSource(ints = {1,2,60,10079,10080})
    void acceptsCooldownWithinInclusiveBounds(int cooldownMinutes) {
        assertThat(AlertCooldownPolicy.requireValid(cooldownMinutes)).isEqualTo(cooldownMinutes);
    }



    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE,-1,0,10081,Integer.MAX_VALUE})
    void rejectsCooldownOutsideInclusiveBounds(int cooldownMinutes) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AlertCooldownPolicy.requireValid(cooldownMinutes))
                .withMessage("cooldownMinutes must be between 1 and 10080");
    }



    @Test
    void rejectsMissingCooldown() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AlertCooldownPolicy.requireValid(null))
                .withMessage("cooldownMinutes must not be null");
    }



    @Test
    void defaultCooldownIsAValidSixtyMinutes() {
        assertThat(AlertCooldownPolicy.requireValid(AlertCooldownPolicy.DEFAULT_MINUTES)).isEqualTo(60);
    }
}