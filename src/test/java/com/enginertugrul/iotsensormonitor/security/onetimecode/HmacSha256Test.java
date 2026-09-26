package com.enginertugrul.iotsensormonitor.security.onetimecode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;



class HmacSha256Test {

    private static final String ENCODED_SECRET = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final String SECRET_DESCRIPTION = "Test HMAC secret";
    private static final String PURPOSE = "email-verification-code";
    private static final String VALUE = "42:01234567";
    private static final String EXPECTED_DIGEST = "c61021d6e2d06905a4cfea4e46c03ab6f6e23a850a98959978e3bc840c734c95";

    private final HmacSha256 hmac = new HmacSha256(ENCODED_SECRET,SECRET_DESCRIPTION);




    @ParameterizedTest
    @CsvSource({
            "email-verification-code,42:01234567,c61021d6e2d06905a4cfea4e46c03ab6f6e23a850a98959978e3bc840c734c95",
            "password-reset-code,42:01234567,0dbbe40cdc7d82d1f9203d9555368fc10ca7baa6e009d93a3762ef1b543a138b",
            "doğrulama,İstanbul:çığ,99c7c2eda0c4591c853e163981dc31dfa8975b58c5bcc19b2eec7f8abbcc047f"
    })
    void producesKnownUtf8Digests(String purpose,String value,String expectedDigest) {
        assertThat(hmac.digest(purpose,value)).isEqualTo(expectedDigest);
        assertThat(hmac.matches(purpose,value,expectedDigest)).isTrue();
    }



    @ParameterizedTest
    @ValueSource(ints = {32,33,64,65})
    void acceptsSecretsAtOrAboveTheMinimumDecodedLength(int byteLength) {
        String encodedSecret = Base64.getEncoder().encodeToString(new byte[byteLength]);
        HmacSha256 configuredHmac = new HmacSha256(encodedSecret,SECRET_DESCRIPTION);

        assertThat(configuredHmac.digest(PURPOSE,VALUE)).matches("[0-9a-f]{64}");
    }



    @Test
    void producesKnownDigestWithASecretLongerThanTheSha256BlockSize() {
        byte[] secretBytes = new byte[131];
        Arrays.fill(secretBytes,(byte) 0xaa);
        String encodedSecret = Base64.getEncoder().encodeToString(secretBytes);
        HmacSha256 configuredHmac = new HmacSha256(encodedSecret,SECRET_DESCRIPTION);

        assertThat(configuredHmac.digest(PURPOSE,VALUE))
                .isEqualTo("8ea12ff20fa179eaaeccc711d710101adda0c19a7bf9ff3462e25dd92a5377b8");
    }



    @Test
    void acceptsSurroundingWhitespaceInTheEncodedSecret() {
        HmacSha256 configuredHmac = new HmacSha256(" \t" + ENCODED_SECRET + "\r\n",SECRET_DESCRIPTION);

        assertThat(configuredHmac.digest(PURPOSE,VALUE)).isEqualTo(EXPECTED_DIGEST);
    }



    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ","\t\r\n","\u2003"})
    void rejectsMissingOrBlankSecrets(String encodedSecret) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new HmacSha256(encodedSecret,SECRET_DESCRIPTION))
                .withMessage("Test HMAC secret must not be blank");
    }



    @ParameterizedTest
    @ValueSource(strings = {"%%%","A","AA=A","AAAA AAAA","****"})
    void rejectsMalformedBase64Secrets(String encodedSecret) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new HmacSha256(encodedSecret,SECRET_DESCRIPTION))
                .withMessage("Test HMAC secret must be valid Base64")
                .withCauseInstanceOf(IllegalArgumentException.class);
    }



    @ParameterizedTest
    @ValueSource(ints = {1,16,31})
    void rejectsSecretsShorterThanThirtyTwoDecodedBytes(int byteLength) {
        String encodedSecret = Base64.getEncoder().encodeToString(new byte[byteLength]);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new HmacSha256(encodedSecret,SECRET_DESCRIPTION))
                .withMessage("Test HMAC secret must contain at least 32 bytes");
    }



    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ","\t\r\n","\u2003"})
    void requiresASecretDescription(String description) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new HmacSha256(ENCODED_SECRET,description))
                .withMessage("secretDescription must not be blank");
    }



    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ","\t\r\n","\u2003"})
    void rejectsMissingOrBlankDigestInputs(String input) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> hmac.digest(input,VALUE))
                .withMessage("purpose must not be blank");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> hmac.digest(PURPOSE,input))
                .withMessage("value must not be blank");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> hmac.matches(input,VALUE,EXPECTED_DIGEST))
                .withMessage("purpose must not be blank");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> hmac.matches(PURPOSE,input,EXPECTED_DIGEST))
                .withMessage("value must not be blank");
    }



    @Test
    void separatesPurposeFromValueInsteadOfSimplyConcatenatingThem() {
        assertThat(hmac.digest("ab","c")).isNotEqualTo(hmac.digest("a","bc"));
    }



    @Test
    void bindsTheDigestToThePurposeValueAndSecret() {
        String otherSecret = Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8));
        HmacSha256 otherHmac = new HmacSha256(otherSecret,SECRET_DESCRIPTION);

        assertThat(hmac.matches("password-reset-code",VALUE,EXPECTED_DIGEST)).isFalse();
        assertThat(hmac.matches(PURPOSE,"43:01234567",EXPECTED_DIGEST)).isFalse();
        assertThat(hmac.matches(PURPOSE,"42:01234568",EXPECTED_DIGEST)).isFalse();
        assertThat(otherHmac.matches(PURPOSE,VALUE,EXPECTED_DIGEST)).isFalse();
    }



    @Test
    void acceptsUppercaseHexAndSurroundingWhitespaceInExpectedDigests() {
        String submittedDigest = " \t" + EXPECTED_DIGEST.toUpperCase(Locale.ROOT) + "\r\n";

        assertThat(hmac.matches(PURPOSE,VALUE,submittedDigest)).isTrue();
    }



    @ParameterizedTest
    @NullAndEmptySource
    @MethodSource("invalidDigests")
    void returnsFalseForMissingMalformedOrIncorrectDigests(String expectedDigest) {
        assertThat(hmac.matches(PURPOSE,VALUE,expectedDigest)).isFalse();
    }



    @Test
    void remainsStableAcrossInterleavedDigestCalculations() {
        assertThat(hmac.digest(PURPOSE,VALUE)).isEqualTo(EXPECTED_DIGEST);

        hmac.digest("password-reset-code","43:99999999");

        assertThat(hmac.digest(PURPOSE,VALUE)).isEqualTo(EXPECTED_DIGEST);
        assertThat(hmac.matches(PURPOSE,VALUE,EXPECTED_DIGEST)).isTrue();
    }



    private static Stream<String> invalidDigests() {
        return Stream.of(
                " ",
                "\t\r\n",
                "not-hex",
                "abc",
                "0".repeat(62),
                "0".repeat(63),
                "0".repeat(64),
                "0".repeat(66),
                "g".repeat(64),
                "0x" + EXPECTED_DIGEST,
                EXPECTED_DIGEST.substring(0,32) + " " + EXPECTED_DIGEST.substring(32)
        );
    }
}