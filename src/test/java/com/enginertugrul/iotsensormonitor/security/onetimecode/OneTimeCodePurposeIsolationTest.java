package com.enginertugrul.iotsensormonitor.security.onetimecode;

import com.enginertugrul.iotsensormonitor.security.recovery.PasswordRecoveryHmac;
import com.enginertugrul.iotsensormonitor.security.recovery.SecureRandomPasswordRecoveryCodeGenerator;
import com.enginertugrul.iotsensormonitor.security.verification.EmailVerificationHmac;
import com.enginertugrul.iotsensormonitor.security.verification.SecureRandomEmailVerificationCodeGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;



class OneTimeCodePurposeIsolationTest {

    private static final String ENCODED_SECRET = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final String RAW_CODE = "01234567";
    private static final String VERIFICATION_HASH = "c61021d6e2d06905a4cfea4e46c03ab6f6e23a850a98959978e3bc840c734c95";
    private static final String RECOVERY_HASH = "0dbbe40cdc7d82d1f9203d9555368fc10ca7baa6e009d93a3762ef1b543a138b";

    private final EmailVerificationHmac verificationHmac = new EmailVerificationHmac(ENCODED_SECRET);
    private final PasswordRecoveryHmac recoveryHmac = new PasswordRecoveryHmac(ENCODED_SECRET);
    private final SecureRandomEmailVerificationCodeGenerator verificationGenerator = new SecureRandomEmailVerificationCodeGenerator(verificationHmac);
    private final SecureRandomPasswordRecoveryCodeGenerator recoveryGenerator = new SecureRandomPasswordRecoveryCodeGenerator(recoveryHmac);



    @Test
    void concreteHmacWrappersProduceTheExpectedPurposeDigests() {
        assertThat(verificationHmac.digest("email-verification-code","42:" + RAW_CODE)).isEqualTo(VERIFICATION_HASH);
        assertThat(recoveryHmac.digest("password-reset-code","42:" + RAW_CODE)).isEqualTo(RECOVERY_HASH);
    }



    @Test
    void identicalUserDigitsAndSecretRemainSeparatedByPurpose() {
        assertThat(verificationGenerator.matches(42L,RAW_CODE,VERIFICATION_HASH)).isTrue();
        assertThat(recoveryGenerator.matches(42L,RAW_CODE,RECOVERY_HASH)).isTrue();

        assertThat(verificationGenerator.matches(42L,RAW_CODE,RECOVERY_HASH)).isFalse();
        assertThat(recoveryGenerator.matches(42L,RAW_CODE,VERIFICATION_HASH)).isFalse();
    }



    @Test
    void generatedVerificationCodeIsBoundToItsPurposeAndUser() {
        GeneratedOneTimeCode generated = verificationGenerator.generate(42L);

        assertThat(generated.rawCode()).matches("[0-9]{8}");
        assertThat(generated.codeHash()).isEqualTo(verificationHmac.digest("email-verification-code","42:" + generated.rawCode()));
        assertThat(verificationGenerator.matches(42L,generated.rawCode(),generated.codeHash())).isTrue();
        assertThat(verificationGenerator.matches(43L,generated.rawCode(),generated.codeHash())).isFalse();
        assertThat(recoveryGenerator.matches(42L,generated.rawCode(),generated.codeHash())).isFalse();
    }



    @Test
    void generatedRecoveryCodeIsBoundToItsPurposeAndUser() {
        GeneratedOneTimeCode generated = recoveryGenerator.generate(42L);

        assertThat(generated.rawCode()).matches("[0-9]{8}");
        assertThat(generated.codeHash()).isEqualTo(recoveryHmac.digest("password-reset-code","42:" + generated.rawCode()));
        assertThat(recoveryGenerator.matches(42L,generated.rawCode(),generated.codeHash())).isTrue();
        assertThat(recoveryGenerator.matches(43L,generated.rawCode(),generated.codeHash())).isFalse();
        assertThat(verificationGenerator.matches(42L,generated.rawCode(),generated.codeHash())).isFalse();
    }



    @Test
    void bothConcreteGeneratorsHonorTheirConfiguredSecret() {
        String otherSecret = Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8));
        EmailVerificationHmac otherVerificationHmac = new EmailVerificationHmac(otherSecret);
        PasswordRecoveryHmac otherRecoveryHmac = new PasswordRecoveryHmac(otherSecret);
        SecureRandomEmailVerificationCodeGenerator otherVerificationGenerator = new SecureRandomEmailVerificationCodeGenerator(otherVerificationHmac);
        SecureRandomPasswordRecoveryCodeGenerator otherRecoveryGenerator = new SecureRandomPasswordRecoveryCodeGenerator(otherRecoveryHmac);

        String otherVerificationHash = otherVerificationHmac.digest("email-verification-code","42:" + RAW_CODE);
        String otherRecoveryHash = otherRecoveryHmac.digest("password-reset-code","42:" + RAW_CODE);

        assertThat(otherVerificationGenerator.matches(42L,RAW_CODE,otherVerificationHash)).isTrue();
        assertThat(otherRecoveryGenerator.matches(42L,RAW_CODE,otherRecoveryHash)).isTrue();
        assertThat(otherVerificationGenerator.matches(42L,RAW_CODE,VERIFICATION_HASH)).isFalse();
        assertThat(otherRecoveryGenerator.matches(42L,RAW_CODE,RECOVERY_HASH)).isFalse();
    }



    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ","\t\r\n","%%%","c2hvcnQ="})
    void bothConcreteHmacWrappersRejectInvalidSecrets(String encodedSecret) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EmailVerificationHmac(encodedSecret))
                .withMessageStartingWith("Email verification HMAC secret");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PasswordRecoveryHmac(encodedSecret))
                .withMessageStartingWith("Password recovery HMAC secret");
    }
}