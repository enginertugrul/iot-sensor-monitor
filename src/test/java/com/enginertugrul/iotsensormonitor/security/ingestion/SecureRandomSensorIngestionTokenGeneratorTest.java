package com.enginertugrul.iotsensormonitor.security.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;



class SecureRandomSensorIngestionTokenGeneratorTest {

    private static final String ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    private final SecureRandomSensorIngestionTokenGenerator generator = new SecureRandomSensorIngestionTokenGenerator();



    @Test
    void encodesExactly32RandomBytesAsUnpaddedUrlSafeBase64() throws NoSuchAlgorithmException {
        byte[] expectedBytes = new byte[32];
        Arrays.fill(expectedBytes,(byte) 0xff);
        expectedBytes[0] = (byte) 0xfb;

        try (MockedConstruction<SecureRandom> randoms = mockConstruction(SecureRandom.class,(random,context) -> {
            doAnswer(invocation -> {
                byte[] destination = invocation.getArgument(0);
                assertThat(destination).hasSize(32);
                System.arraycopy(expectedBytes,0,destination,0,expectedBytes.length);
                return null;
            }).when(random).nextBytes(any(byte[].class));
        })) {
            SecureRandomSensorIngestionTokenGenerator controlledGenerator = new SecureRandomSensorIngestionTokenGenerator();

            GeneratedSensorIngestionToken generated = controlledGenerator.generate();

            assertThat(generated.rawToken()).isEqualTo("-" + "_".repeat(41) + "8");
            assertThat(generated.rawToken()).matches("[A-Za-z0-9_-]{43}");
            assertThat(generated.rawToken()).doesNotContain("=","+","/");
            assertThat(Base64.getUrlDecoder().decode(generated.rawToken())).containsExactly(expectedBytes);
            assertThat(generated.tokenHash()).isEqualTo(sha256(generated.rawToken()));
            assertThat(randoms.constructed()).hasSize(1);
            verify(randoms.constructed().getFirst()).nextBytes(any(byte[].class));
        }
    }



    @Test
    void generatesUsableTokenWithMatchingLowercaseSha256Hash() throws NoSuchAlgorithmException {
        GeneratedSensorIngestionToken generated = generator.generate();

        assertThat(generated.rawToken()).matches("[A-Za-z0-9_-]{43}");
        assertThat(Base64.getUrlDecoder().decode(generated.rawToken())).hasSize(32);
        assertThat(generated.tokenHash()).matches("[0-9a-f]{64}");
        assertThat(generated.tokenHash()).isEqualTo(sha256(generated.rawToken()));
        assertThat(generator.hash(generated.rawToken())).isEqualTo(generated.tokenHash());
        assertThat(generator.hash(" \t" + generated.rawToken() + "\r\n")).isEqualTo(generated.tokenHash());
    }



    @Test
    void matchesKnownSha256Vector() {
        assertThat(generator.hash("abc")).isEqualTo(ABC_SHA256);
    }



    @ParameterizedTest
    @ValueSource(strings = {" abc ","\tabc\r\n"," \t abc \r\n"})
    void trimsSurroundingWhitespaceBeforeHashing(String rawToken) {
        assertThat(generator.hash(rawToken)).isEqualTo(ABC_SHA256);
    }



    @ParameterizedTest
    @ValueSource(strings = {"ABC","a bc","abc-_/","sensör-İstanbul"})
    void hashesExactUtf8ContentWithoutChangingCaseOrInternalCharacters(String rawToken) throws NoSuchAlgorithmException {
        assertThat(generator.hash(rawToken)).isEqualTo(sha256(rawToken));
        assertThat(generator.hash(rawToken)).isNotEqualTo(ABC_SHA256);
    }



    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ","\t\r\n","\u2003"})
    void rejectsNullEmptyAndBlankTokens(String rawToken) {
        assertThatThrownBy(() -> generator.hash(rawToken))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("sensor ingestion token must not be blank");
    }



    private static String sha256(String value) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}