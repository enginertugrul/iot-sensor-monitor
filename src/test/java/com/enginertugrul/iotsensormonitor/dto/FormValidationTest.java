package com.enginertugrul.iotsensormonitor.dto;

import com.enginertugrul.iotsensormonitor.dto.alert.MotionEventAlertRuleForm;
import com.enginertugrul.iotsensormonitor.dto.alert.NumericThresholdAlertRuleForm;
import com.enginertugrul.iotsensormonitor.dto.auth.EmailVerificationCodeForm;
import com.enginertugrul.iotsensormonitor.dto.auth.EmailVerificationRequestForm;
import com.enginertugrul.iotsensormonitor.dto.auth.PasswordRecoveryRequestForm;
import com.enginertugrul.iotsensormonitor.dto.auth.PasswordResetForm;
import com.enginertugrul.iotsensormonitor.dto.auth.RegisterUserForm;
import com.enginertugrul.iotsensormonitor.dto.sensor.SensorCreateForm;
import com.enginertugrul.iotsensormonitor.dto.sensor.SensorUpdateForm;
import com.enginertugrul.iotsensormonitor.dto.user.PasswordChangeForm;
import com.enginertugrul.iotsensormonitor.dto.user.UserPreferencesForm;
import com.enginertugrul.iotsensormonitor.entity.alert.ComparisonOperator;
import com.enginertugrul.iotsensormonitor.entity.user.PreferredLanguage;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.validation.IsFiniteDouble;
import com.enginertugrul.iotsensormonitor.validation.PasswordsMatch;
import com.enginertugrul.iotsensormonitor.validation.ValidZoneId;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.annotation.Annotation;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class FormValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;



    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }



    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }



    @ParameterizedTest
    @MethodSource("validForms")
    void acceptsCompleteValidForms(Object form) {
        assertThat(validator.validate(form)).isEmpty();
    }



    @ParameterizedTest
    @MethodSource("notBlankFields")
    void requiresNonblankText(Class<?> formType,String property) {
        for (String value : new String[]{null,""," ","\t\r\n"}) {
            assertValueViolation(formType,property,value,NotBlank.class);
        }
    }



    @ParameterizedTest
    @MethodSource("notNullFields")
    void requiresNonNullValues(Class<?> formType,String property) {
        var violations = validator.validateValue(formType,property,null);

        assertThat(violations).hasSize(1);
        assertViolation(violations,property,NotNull.class);
    }



    @ParameterizedTest
    @MethodSource("sizeConstraints")
    void enforcesSizeBoundariesIndependentlyOfFormatConstraints(Class<?> formType,String property,int minimum,int maximum) {
        for (int length : new int[]{minimum,maximum}) {
            assertThat(validator.validateValue(formType,property,"x".repeat(length)))
                    .allSatisfy(violation -> assertThat(violation.getConstraintDescriptor().getAnnotation()).isNotInstanceOf(Size.class));
        }

        if (minimum > 0) {
            assertValueViolation(formType,property,"x".repeat(minimum - 1),Size.class);
        }

        assertValueViolation(formType,property,"x".repeat(maximum + 1),Size.class);
    }



    @ParameterizedTest
    @MethodSource("emailForms")
    void validatesEmailSyntaxOnEveryEmailForm(Class<?> formType) {
        for (String email : new String[]{"owner@example.com","owner+sensor@example.co.uk","OWNER@EXAMPLE.COM"}) {
            assertThat(validator.validateValue(formType,"email",email)).isEmpty();
        }

        for (String email : new String[]{"not-an-email","owner@","@example.com","owner@@example.com"}) {
            assertValueViolation(formType,"email",email,Email.class);
        }
    }



    @ParameterizedTest
    @MethodSource("timezoneFields")
    void wiresTimezoneValidationToAccountAndSensorForms(Class<?> formType,String property) {
        for (String timezone : new String[]{"UTC","Europe/Istanbul","Asia/Kathmandu","+03:00"}) {
            assertThat(validator.validateValue(formType,property,timezone)).isEmpty();
        }

        for (String timezone : new String[]{"Europe/Not_A_Zone","utc"," UTC","UTC "}) {
            assertValueViolation(formType,property,timezone,ValidZoneId.class);
        }
    }



    @ParameterizedTest
    @MethodSource("mismatchedPasswordForms")
    void attachesPasswordMismatchToConfirmationWithTheCorrectMessage(Object form,String messageTemplate) {
        assertThat(validator.validate(form)).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath().toString()).isEqualTo("confirmPassword");
            assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(PasswordsMatch.class);
            assertThat(violation.getMessageTemplate()).isEqualTo(messageTemplate);
        });
    }



    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ","\t\r\n"})
    void missingConfirmationProducesRequiredErrorWithoutDuplicateMismatch(String confirmation) {
        RegisterUserForm registration = registrationForm();
        registration.setConfirmPassword(confirmation);
        assertOnlyViolation(registration,"confirmPassword",NotBlank.class);

        PasswordResetForm reset = resetForm();
        reset.setConfirmPassword(confirmation);
        assertOnlyViolation(reset,"confirmPassword",NotBlank.class);

        PasswordChangeForm change = changeForm();
        change.setConfirmPassword(confirmation);
        assertOnlyViolation(change,"confirmPassword",NotBlank.class);
    }



    @ParameterizedTest
    @ValueSource(strings = {"00000000","12345678","99999999"})
    void acceptsExactlyEightAsciiDigitsForVerificationAndRecoveryCodes(String code) {
        EmailVerificationCodeForm verification = new EmailVerificationCodeForm();
        verification.setCode(code);

        PasswordResetForm reset = resetForm();
        reset.setCode(code);

        assertThat(validator.validate(verification)).isEmpty();
        assertThat(validator.validate(reset)).isEmpty();
    }



    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "1234567",
            "123456789",
            "1234abcd",
            " 12345678",
            "12345678 ",
            "１２３４５６７８",
            "١٢٣٤٥٦٧٨"
    })
    void rejectsMalformedVerificationAndRecoveryCodes(String code) {
        assertValueViolation(EmailVerificationCodeForm.class,"code",code,Pattern.class);
        assertValueViolation(PasswordResetForm.class,"code",code,Pattern.class);
    }



    @ParameterizedTest
    @ValueSource(ints = {1,60,10080})
    void acceptsInclusiveCooldownBoundsForBothAlertForms(int minutes) {
        NumericThresholdAlertRuleForm numeric = numericRuleForm();
        numeric.setCooldownMinutes(minutes);

        MotionEventAlertRuleForm motion = motionRuleForm();
        motion.setCooldownMinutes(minutes);

        assertThat(validator.validate(numeric)).isEmpty();
        assertThat(validator.validate(motion)).isEmpty();
    }



    @Test
    void rejectsCooldownValuesImmediatelyOutsideBounds() {
        assertValueViolation(NumericThresholdAlertRuleForm.class,"cooldownMinutes",0,Min.class);
        assertValueViolation(NumericThresholdAlertRuleForm.class,"cooldownMinutes",10081,Max.class);
        assertValueViolation(MotionEventAlertRuleForm.class,"cooldownMinutes",0,Min.class);
        assertValueViolation(MotionEventAlertRuleForm.class,"cooldownMinutes",10081,Max.class);
    }



    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY})
    void rejectsNonFiniteNumericAlertThresholds(double threshold) {
        NumericThresholdAlertRuleForm form = numericRuleForm();
        form.setThresholdValue(threshold);

        assertOnlyViolation(form,"thresholdValue",IsFiniteDouble.class);
    }



    private static Stream<Object> validForms() {
        EmailVerificationRequestForm verificationRequest = new EmailVerificationRequestForm();
        verificationRequest.setEmail("owner@example.com");

        EmailVerificationCodeForm verificationCode = new EmailVerificationCodeForm();
        verificationCode.setCode("01234567");

        PasswordRecoveryRequestForm recoveryRequest = new PasswordRecoveryRequestForm();
        recoveryRequest.setEmail("owner@example.com");

        return Stream.of(
                registrationForm(),verificationRequest,verificationCode,recoveryRequest,
                resetForm(),changeForm(),preferencesForm(),sensorCreateForm(),
                sensorUpdateForm(),numericRuleForm(),motionRuleForm());
    }



    private static Stream<Arguments> notBlankFields() {
        return Stream.of(
                fields(RegisterUserForm.class,"email","password","confirmPassword","preferredTimezone"),
                fields(EmailVerificationRequestForm.class,"email"),
                fields(PasswordRecoveryRequestForm.class,"email"),
                fields(PasswordResetForm.class,"code","password","confirmPassword"),
                fields(PasswordChangeForm.class,"currentPassword","password","confirmPassword"),
                fields(UserPreferencesForm.class,"preferredTimezone"),
                fields(SensorCreateForm.class,"name","city","district","installationLocation","timezone"),
                fields(SensorUpdateForm.class,"name","city","district","installationLocation","timezone")
        ).flatMap(stream -> stream);
    }



    private static Stream<Arguments> notNullFields() {
        return Stream.of(
                fields(RegisterUserForm.class,"preferredLanguage","preferredTemperatureUnit"),
                fields(EmailVerificationCodeForm.class,"code"),
                fields(UserPreferencesForm.class,"preferredLanguage","temperatureUnit"),
                fields(SensorCreateForm.class,"type"),
                fields(NumericThresholdAlertRuleForm.class,"sensorId","comparisonOperator","thresholdValue","cooldownMinutes"),
                fields(MotionEventAlertRuleForm.class,"sensorId","cooldownMinutes")
        ).flatMap(stream -> stream);
    }



    private static Stream<Arguments> sizeConstraints() {
        return Stream.of(
                Arguments.of(RegisterUserForm.class,"email",0,320),
                Arguments.of(EmailVerificationRequestForm.class,"email",0,320),
                Arguments.of(PasswordRecoveryRequestForm.class,"email",0,320),
                Arguments.of(RegisterUserForm.class,"password",8,72),
                Arguments.of(PasswordResetForm.class,"password",8,72),
                Arguments.of(PasswordChangeForm.class,"currentPassword",8,72),
                Arguments.of(PasswordChangeForm.class,"password",8,72),
                Arguments.of(RegisterUserForm.class,"preferredTimezone",0,64),
                Arguments.of(UserPreferencesForm.class,"preferredTimezone",0,64),
                Arguments.of(SensorCreateForm.class,"timezone",0,64),
                Arguments.of(SensorUpdateForm.class,"timezone",0,64),
                Arguments.of(SensorCreateForm.class,"name",0,100),
                Arguments.of(SensorCreateForm.class,"city",0,100),
                Arguments.of(SensorCreateForm.class,"district",0,100),
                Arguments.of(SensorCreateForm.class,"installationLocation",0,100),
                Arguments.of(SensorUpdateForm.class,"name",0,100),
                Arguments.of(SensorUpdateForm.class,"city",0,100),
                Arguments.of(SensorUpdateForm.class,"district",0,100),
                Arguments.of(SensorUpdateForm.class,"installationLocation",0,100));
    }



    private static Stream<Class<?>> emailForms() {
        return Stream.of(RegisterUserForm.class,EmailVerificationRequestForm.class,PasswordRecoveryRequestForm.class);
    }



    private static Stream<Arguments> timezoneFields() {
        return Stream.of(
                Arguments.of(RegisterUserForm.class,"preferredTimezone"),
                Arguments.of(UserPreferencesForm.class,"preferredTimezone"),
                Arguments.of(SensorCreateForm.class,"timezone"),
                Arguments.of(SensorUpdateForm.class,"timezone"));
    }



    private static Stream<Arguments> mismatchedPasswordForms() {
        RegisterUserForm registration = registrationForm();
        registration.setConfirmPassword("different-password");

        PasswordResetForm reset = resetForm();
        reset.setConfirmPassword("different-password");

        PasswordChangeForm change = changeForm();
        change.setConfirmPassword("different-password");

        return Stream.of(
                Arguments.of(registration,"{auth.passwordMismatch}"),
                Arguments.of(reset,"{passwordRecovery.passwordMismatch}"),
                Arguments.of(change,"{passwordChange.passwordMismatch}"));
    }



    private static Stream<Arguments> fields(Class<?> formType,String... properties) {
        return Stream.of(properties).map(property -> Arguments.of(formType,property));
    }



    private static RegisterUserForm registrationForm() {
        RegisterUserForm form = new RegisterUserForm();
        form.setEmail("owner@example.com");
        form.setPassword("password123");
        form.setConfirmPassword("password123");
        return form;
    }



    private static PasswordResetForm resetForm() {
        PasswordResetForm form = new PasswordResetForm();
        form.setCode("01234567");
        form.setPassword("password123");
        form.setConfirmPassword("password123");
        return form;
    }



    private static PasswordChangeForm changeForm() {
        PasswordChangeForm form = new PasswordChangeForm();
        form.setCurrentPassword("old-password123");
        form.setPassword("password123");
        form.setConfirmPassword("password123");
        return form;
    }



    private static UserPreferencesForm preferencesForm() {
        UserPreferencesForm form = new UserPreferencesForm();
        form.setPreferredLanguage(PreferredLanguage.TURKISH);
        form.setTemperatureUnit(TemperatureUnit.KELVIN);
        form.setPreferredTimezone("Europe/Istanbul");
        return form;
    }



    private static SensorCreateForm sensorCreateForm() {
        SensorCreateForm form = new SensorCreateForm();
        form.setName("Living room");
        form.setCity("Istanbul");
        form.setDistrict("Kadikoy");
        form.setInstallationLocation("Window");
        return form;
    }



    private static SensorUpdateForm sensorUpdateForm() {
        SensorUpdateForm form = new SensorUpdateForm();
        form.setName("Living room");
        form.setCity("Istanbul");
        form.setDistrict("Kadikoy");
        form.setInstallationLocation("Window");
        form.setTimezone("Europe/Istanbul");
        return form;
    }



    private static NumericThresholdAlertRuleForm numericRuleForm() {
        NumericThresholdAlertRuleForm form = new NumericThresholdAlertRuleForm();
        form.setSensorId(1L);
        form.setComparisonOperator(ComparisonOperator.ABOVE);
        form.setThresholdValue(25.5);
        form.setCooldownMinutes(60);
        return form;
    }



    private static MotionEventAlertRuleForm motionRuleForm() {
        MotionEventAlertRuleForm form = new MotionEventAlertRuleForm();
        form.setSensorId(1L);
        form.setCooldownMinutes(60);
        return form;
    }



    private static void assertValueViolation(Class<?> formType,String property,Object value,Class<? extends Annotation> constraint) {
        assertViolation(validator.validateValue(formType,property,value),property,constraint);
    }



    private static void assertOnlyViolation(Object form,String property,Class<? extends Annotation> constraint) {
        var violations = validator.validate(form);

        assertThat(violations).hasSize(1);
        assertViolation(violations,property,constraint);
    }



    private static void assertViolation(Set<? extends ConstraintViolation<?>> violations,String property,Class<? extends Annotation> constraint) {
        assertThat(violations).anySatisfy(violation -> {
            assertThat(violation.getPropertyPath().toString()).isEqualTo(property);
            assertThat(violation.getConstraintDescriptor().getAnnotation().annotationType()).isEqualTo(constraint);
        });
    }
}