package com.enginertugrul.iotsensormonitor;

import com.enginertugrul.iotsensormonitor.dto.auth.PasswordResetForm;
import com.enginertugrul.iotsensormonitor.dto.reading.HumidityReadingRequest;
import com.enginertugrul.iotsensormonitor.dto.reading.MotionReadingRequest;
import com.enginertugrul.iotsensormonitor.dto.reading.TemperatureReadingRequest;
import com.enginertugrul.iotsensormonitor.dto.sensor.CreatedSensorDTO;
import com.enginertugrul.iotsensormonitor.dto.user.PasswordChangeForm;
import com.enginertugrul.iotsensormonitor.entity.user.PreferredLanguage;
import com.enginertugrul.iotsensormonitor.security.ingestion.GeneratedSensorIngestionToken;
import com.enginertugrul.iotsensormonitor.security.onetimecode.GeneratedOneTimeCode;
import com.enginertugrul.iotsensormonitor.service.user.recovery.PasswordRecoveryCodeDelivery;
import com.enginertugrul.iotsensormonitor.service.user.verification.EmailVerificationCodeDelivery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class SensitiveValueContractTest {

    private static final String RAW_TOKEN = "raw-ingestion-token-for-contract-test";
    private static final String TOKEN_HASH = "a".repeat(64);
    private static final String RAW_CODE = "01234567";
    private static final String CODE_HASH = "b".repeat(64);
    private static final String RECIPIENT_EMAIL = "owner@example.com";
    private static final Instant EXPIRES_AT = CREATED_AT.plusSeconds(600);



    @Test
    void generatedIngestionTokenPreservesValuesButRedactsBothFromDiagnosticOutput() {
        GeneratedSensorIngestionToken token = new GeneratedSensorIngestionToken(RAW_TOKEN,TOKEN_HASH);

        assertThat(token.rawToken()).isEqualTo(RAW_TOKEN);
        assertThat(token.tokenHash()).isEqualTo(TOKEN_HASH);
        assertThat(token.toString())
                .contains("rawToken=[REDACTED]","tokenHash=[REDACTED]")
                .doesNotContain(RAW_TOKEN,TOKEN_HASH);
    }



    @Test
    void generatedOneTimeCodePreservesValuesButRedactsBothFromDiagnosticOutput() {
        GeneratedOneTimeCode code = new GeneratedOneTimeCode(RAW_CODE,CODE_HASH);

        assertThat(code.rawCode()).isEqualTo(RAW_CODE);
        assertThat(code.codeHash()).isEqualTo(CODE_HASH);
        assertThat(code.toString())
                .contains("rawCode=[REDACTED]","codeHash=[REDACTED]")
                .doesNotContain(RAW_CODE,CODE_HASH);
    }



    @Test
    void createdSensorRedactsItsTokenWhileRetainingDiagnosticContext() {
        CreatedSensorDTO created = new CreatedSensorDTO(42L,"Living room",RAW_TOKEN);

        assertThat(created.rawIngestionToken()).isEqualTo(RAW_TOKEN);
        assertThat(created.toString())
                .contains("sensorId=42","sensorName=Living room","rawIngestionToken=[REDACTED]")
                .doesNotContain(RAW_TOKEN);
    }



    @Test
    void readingRequestsRedactTokensWhileRetainingMeasurementContext() {
        TemperatureReadingRequest temperature = new TemperatureReadingRequest(RAW_TOKEN,21.5,CREATED_AT);
        HumidityReadingRequest humidity = new HumidityReadingRequest(RAW_TOKEN,48.5,CREATED_AT);
        MotionReadingRequest motion = new MotionReadingRequest(RAW_TOKEN,false,CREATED_AT);

        assertThat(temperature.sensorToken()).isEqualTo(RAW_TOKEN);
        assertThat(humidity.sensorToken()).isEqualTo(RAW_TOKEN);
        assertThat(motion.sensorToken()).isEqualTo(RAW_TOKEN);

        for (Object request : List.of(temperature,humidity,motion)) {
            assertThat(request.toString())
                    .contains("sensorToken=[REDACTED]","recordedAt=" + CREATED_AT)
                    .doesNotContain(RAW_TOKEN);
        }

        assertThat(temperature.toString()).contains("celsiusValue=21.5");
        assertThat(humidity.toString()).contains("humidityPercentage=48.5");
        assertThat(motion.toString()).contains("motionDetected=false");
    }



    @ParameterizedTest
    @EnumSource(PreferredLanguage.class)
    void deliveriesNormalizeRecipientsPreserveDeliveryDataAndRedactSensitiveOutput(PreferredLanguage language) {
        String submittedEmail = " \tOWNER@EXAMPLE.COM\r\n";
        EmailVerificationCodeDelivery verification = new EmailVerificationCodeDelivery(42L,submittedEmail,language,RAW_CODE,EXPIRES_AT);
        PasswordRecoveryCodeDelivery recovery = new PasswordRecoveryCodeDelivery(42L,submittedEmail,language,RAW_CODE,EXPIRES_AT);

        assertThat(verification.userId()).isEqualTo(42L);
        assertThat(verification.recipientEmail()).isEqualTo(RECIPIENT_EMAIL);
        assertThat(verification.preferredLanguage()).isEqualTo(language);
        assertThat(verification.rawCode()).isEqualTo(RAW_CODE);
        assertThat(verification.expiresAt()).isEqualTo(EXPIRES_AT);

        assertThat(recovery.userId()).isEqualTo(42L);
        assertThat(recovery.recipientEmail()).isEqualTo(RECIPIENT_EMAIL);
        assertThat(recovery.preferredLanguage()).isEqualTo(language);
        assertThat(recovery.rawCode()).isEqualTo(RAW_CODE);
        assertThat(recovery.expiresAt()).isEqualTo(EXPIRES_AT);

        for (Object delivery : List.of(verification,recovery)) {
            assertThat(delivery.toString())
                    .contains("userId=42","recipientEmail=[REDACTED]","rawCode=[REDACTED]","expiresAt=" + EXPIRES_AT)
                    .doesNotContain(RECIPIENT_EMAIL,RAW_CODE,"OWNER@EXAMPLE.COM");
        }
    }



    @ParameterizedTest
    @ValueSource(strings = {"00000000","01234567","99999999"})
    void generatedCodesAndDeliveriesPreserveExactlyEightAsciiDigits(String rawCode) {
        GeneratedOneTimeCode generated = new GeneratedOneTimeCode(rawCode,CODE_HASH);
        EmailVerificationCodeDelivery verification = new EmailVerificationCodeDelivery(1L,RECIPIENT_EMAIL,PreferredLanguage.ENGLISH,rawCode,EXPIRES_AT);
        PasswordRecoveryCodeDelivery recovery = new PasswordRecoveryCodeDelivery(1L,RECIPIENT_EMAIL,PreferredLanguage.ENGLISH,rawCode,EXPIRES_AT);

        assertThat(generated.rawCode()).isEqualTo(rawCode);
        assertThat(verification.rawCode()).isEqualTo(rawCode);
        assertThat(recovery.rawCode()).isEqualTo(rawCode);
    }



    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ",
            "\t\r\n",
            "1234567",
            "123456789",
            "1234abcd",
            " 01234567",
            "01234567 ",
            "１２３４５６７８",
            "١٢٣٤٥٦٧٨"
    })
    void generatedCodesAndDeliveriesRejectMissingOrMalformedCodes(String rawCode) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new GeneratedOneTimeCode(rawCode,CODE_HASH))
                .withMessage("rawCode must contain exactly eight digits");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EmailVerificationCodeDelivery(1L,RECIPIENT_EMAIL,PreferredLanguage.ENGLISH,rawCode,EXPIRES_AT))
                .withMessage("rawCode must contain exactly eight digits");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PasswordRecoveryCodeDelivery(1L,RECIPIENT_EMAIL,PreferredLanguage.ENGLISH,rawCode,EXPIRES_AT))
                .withMessage("rawCode must contain exactly eight digits");
    }



    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ","\t\r\n","\u2003"})
    void generatedOneTimeCodeRejectsMissingOrBlankHash(String codeHash) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new GeneratedOneTimeCode(RAW_CODE,codeHash))
                .withMessage("codeHash must not be blank");
    }



    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {Long.MIN_VALUE,-1,0})
    void deliveriesRejectMissingOrNonPositiveUserIds(Long userId) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EmailVerificationCodeDelivery(userId,RECIPIENT_EMAIL,PreferredLanguage.ENGLISH,RAW_CODE,EXPIRES_AT))
                .withMessage("userId must be positive");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PasswordRecoveryCodeDelivery(userId,RECIPIENT_EMAIL,PreferredLanguage.ENGLISH,RAW_CODE,EXPIRES_AT))
                .withMessage("userId must be positive");
    }



    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ","\t\r\n","\u2003"})
    void deliveriesRejectMissingOrBlankRecipients(String recipientEmail) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EmailVerificationCodeDelivery(1L,recipientEmail,PreferredLanguage.ENGLISH,RAW_CODE,EXPIRES_AT))
                .withMessage("email must not be blank");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PasswordRecoveryCodeDelivery(1L,recipientEmail,PreferredLanguage.ENGLISH,RAW_CODE,EXPIRES_AT))
                .withMessage("email must not be blank");
    }



    @Test
    void deliveriesRequirePreferredLanguage() {
        assertThatNullPointerException()
                .isThrownBy(() -> new EmailVerificationCodeDelivery(1L,RECIPIENT_EMAIL,null,RAW_CODE,EXPIRES_AT))
                .withMessage("preferredLanguage must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> new PasswordRecoveryCodeDelivery(1L,RECIPIENT_EMAIL,null,RAW_CODE,EXPIRES_AT))
                .withMessage("preferredLanguage must not be null");
    }



    @Test
    void deliveriesRequireExpiryTimestamp() {
        assertThatNullPointerException()
                .isThrownBy(() -> new EmailVerificationCodeDelivery(1L,RECIPIENT_EMAIL,PreferredLanguage.ENGLISH,RAW_CODE,null))
                .withMessage("expiresAt must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> new PasswordRecoveryCodeDelivery(1L,RECIPIENT_EMAIL,PreferredLanguage.ENGLISH,RAW_CODE,null))
                .withMessage("expiresAt must not be null");
    }



    @Test
    void clearingPasswordResetFormRemovesCodeAndPasswordsAndIsSafeToRepeat() {
        PasswordResetForm form = new PasswordResetForm();
        form.setCode(RAW_CODE);
        form.setPassword("new-secret-password");
        form.setConfirmPassword("different-secret-confirmation");

        form.clearSensitiveValues();

        assertResetFormCleared(form);

        form.clearSensitiveValues();

        assertResetFormCleared(form);
    }



    @Test
    void clearingPasswordChangeFormRemovesAllPasswordsAndIsSafeToRepeat() {
        PasswordChangeForm form = new PasswordChangeForm();
        form.setCurrentPassword("current-secret-password");
        form.setPassword("new-secret-password");
        form.setConfirmPassword("different-secret-confirmation");

        form.clearSensitiveValues();

        assertChangeFormCleared(form);

        form.clearSensitiveValues();

        assertChangeFormCleared(form);
    }



    private static void assertResetFormCleared(PasswordResetForm form) {
        assertThat(form.getCode()).isNull();
        assertThat(form.getPassword()).isNull();
        assertThat(form.getConfirmPassword()).isNull();
    }



    private static void assertChangeFormCleared(PasswordChangeForm form) {
        assertThat(form.getCurrentPassword()).isNull();
        assertThat(form.getPassword()).isNull();
        assertThat(form.getConfirmPassword()).isNull();
    }
}