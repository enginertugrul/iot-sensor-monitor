package com.enginertugrul.iotsensormonitor.support.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;



class EmailMaskerTest {

    @ParameterizedTest
    @CsvSource({
            "a@example.com,a***@example.com",
            "ab@example.com,a***b@example.com",
            "alice@example.com,a***e@example.com"
    })
    void keepsFirstAndLastLocalCharactersAndTheDomain(String email,String expected) {
        assertThat(EmailMasker.mask(email)).isEqualTo(expected);
    }



    @ParameterizedTest
    @ValueSource(strings = {"@example.com","no-at-sign"})
    void hidesAddressesWithoutALocalPart(String email) {
        assertThat(EmailMasker.mask(email)).isEqualTo("***");
    }



    @Test
    void usesTheLastAtSignAsTheSeparator() {
        assertThat(EmailMasker.mask("first@second@example.com")).isEqualTo("f***d@example.com");
    }
}