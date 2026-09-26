package com.enginertugrul.iotsensormonitor.security.onetimecode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;



class SecureRandomNumericOneTimeCodeGeneratorTest {

    private static final String ENCODED_SECRET = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final String PURPOSE = "email-verification-code";
    private static final String RAW_CODE = "01234567";

    private final HmacSha256 hmac = new HmacSha256(ENCODED_SECRET,"Test HMAC secret");
    private final SecureRandomNumericOneTimeCodeGenerator generator = new SecureRandomNumericOneTimeCodeGenerator(hmac,PURPOSE);
    private final String codeHash = hmac.digest(PURPOSE,"42:" + RAW_CODE);



    @Test
    void requiresAnHmacDigest() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SecureRandomNumericOneTimeCodeGenerator(null,PURPOSE))
                .withMessage("hmacDigest must not be null");
    }



    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ","\t\r\n","\u2003"})
    void requiresANonBlankPurpose(String purpose) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SecureRandomNumericOneTimeCodeGenerator(hmac,purpose))
                .withMessage("codePurpose must not be blank");
    }



    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {Long.MIN_VALUE,-1,0})
    void rejectsInvalidSubjectsForGenerationAndMatching(Long subjectId) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> generator.generate(subjectId))
                .withMessage("subjectId must be positive");

        String candidateHash = hmac.digest(PURPOSE,subjectId + ":" + RAW_CODE);

        assertThat(generator.matches(subjectId,RAW_CODE,candidateHash)).isFalse();
    }



    @ParameterizedTest
    @CsvSource({
            "0,00000000",
            "1,00000001",
            "1234567,01234567",
            "99999999,99999999"
    })
    void formatsRandomBoundaryValuesAsExactlyEightAsciiDigits(int randomValue,String expectedCode) {
        try (MockedConstruction<SecureRandom> randoms = mockConstruction(SecureRandom.class,(random,context) -> when(random.nextInt(100_000_000)).thenReturn(randomValue))) {
            SecureRandomNumericOneTimeCodeGenerator controlledGenerator = new SecureRandomNumericOneTimeCodeGenerator(hmac,PURPOSE);

            GeneratedOneTimeCode generated = controlledGenerator.generate(42L);

            assertThat(generated.rawCode()).isEqualTo(expectedCode);
            assertThat(generated.codeHash()).isEqualTo(hmac.digest(PURPOSE,"42:" + expectedCode));
            assertThat(controlledGenerator.matches(42L,expectedCode,generated.codeHash())).isTrue();
            assertThat(randoms.constructed()).hasSize(1);
            verify(randoms.constructed().getFirst()).nextInt(100_000_000);
        }
    }



    @ParameterizedTest
    @ValueSource(longs = {1,42,Long.MAX_VALUE})
    void generatesUsableCodesBoundToTheFullSubjectId(long subjectId) {
        GeneratedOneTimeCode generated = generator.generate(subjectId);

        assertThat(generated.rawCode()).matches("[0-9]{8}");
        assertThat(generated.codeHash()).matches("[0-9a-f]{64}");
        assertThat(generated.codeHash()).isEqualTo(hmac.digest(PURPOSE,subjectId + ":" + generated.rawCode()));
        assertThat(generator.matches(subjectId,generated.rawCode(),generated.codeHash())).isTrue();
    }



    @Test
    @ResourceLock(Resources.LOCALE)
    void generatesAsciiDigitsIndependentlyOfTheDefaultLocale() {
        Locale originalLocale = Locale.getDefault();
        Locale originalDisplayLocale = Locale.getDefault(Locale.Category.DISPLAY);
        Locale originalFormatLocale = Locale.getDefault(Locale.Category.FORMAT);

        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"));

            GeneratedOneTimeCode generated = generator.generate(42L);

            assertThat(generated.rawCode()).matches("[0-9]{8}");
            assertThat(generator.matches(42L,generated.rawCode(),generated.codeHash())).isTrue();
        } finally {
            Locale.setDefault(originalLocale);
            Locale.setDefault(Locale.Category.DISPLAY,originalDisplayLocale);
            Locale.setDefault(Locale.Category.FORMAT,originalFormatLocale);
        }
    }



    @ParameterizedTest
    @ValueSource(strings = {"00000000","00000001","01234567","99999999"})
    void acceptsEightDigitCodesAndTrimsSurroundingWhitespace(String rawCode) {
        String expectedHash = hmac.digest(PURPOSE,"42:" + rawCode);

        assertThat(generator.matches(42L,rawCode,expectedHash)).isTrue();
        assertThat(generator.matches(42L," \t" + rawCode + "\r\n",expectedHash)).isTrue();
    }



    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ",
            "\t\r\n",
            "1234567",
            "123456789",
            "1234abcd",
            "+1234567",
            "-1234567",
            "12 34567",
            "1234\n5678",
            "１２３４５６７８",
            "١٢٣٤٥٦٧٨"
    })
    void rejectsMalformedCodesEvenWhenTheirHmacMatches(String rawCode) {
        String normalizedCode = rawCode == null ? "" : rawCode.trim();
        String candidateHash = hmac.digest(PURPOSE,"42:" + normalizedCode);

        assertThat(generator.matches(42L,rawCode,candidateHash)).isFalse();
    }



    @Test
    void rejectsAnotherUserOrDifferentCodeDigits() {
        assertThat(generator.matches(43L,RAW_CODE,codeHash)).isFalse();
        assertThat(generator.matches(42L,"01234568",codeHash)).isFalse();
    }



    @Test
    void rejectsAHashCreatedForAnotherPurpose() {
        String otherPurposeHash = hmac.digest("password-reset-code","42:" + RAW_CODE);

        assertThat(generator.matches(42L,RAW_CODE,otherPurposeHash)).isFalse();
    }



    @ParameterizedTest
    @NullAndEmptySource
    @MethodSource("invalidHashes")
    void returnsFalseForMissingMalformedOrIncorrectHashes(String submittedHash) {
        assertThat(generator.matches(42L,RAW_CODE,submittedHash)).isFalse();
    }



    private static Stream<String> invalidHashes() {
        return Stream.of(
                " ",
                "\t\r\n",
                "not-hex",
                "abc",
                "0".repeat(62),
                "0".repeat(63),
                "0".repeat(64),
                "0".repeat(66),
                "g".repeat(64)
        );
    }
}