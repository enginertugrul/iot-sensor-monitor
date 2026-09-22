package com.enginertugrul.iotsensormonitor.entity.user;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.Locale;
import java.util.stream.Stream;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.UPDATED_AT;
import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class AppUserTest {



    @Test
    void createsNormalizedEnabledUserWithDefaultPreferences() {
        AppUser user = new AppUser(" \tOWNER@Example.COM\n "," test-password-hash ",CREATED_AT);

        assertThat(user.getId()).isNull();
        assertThat(user.getEmail()).isEqualTo("owner@example.com");
        assertThat(user.getPasswordHash()).isEqualTo("test-password-hash");
        assertThat(user.getPreferredLanguage()).isEqualTo(PreferredLanguage.ENGLISH);
        assertThat(user.getPreferredTemperatureUnit()).isEqualTo(TemperatureUnit.CELSIUS);
        assertThat(user.getPreferredTimezone()).isEqualTo("UTC");
        assertThat(user.isEnabled()).isTrue();
        assertThat(user.isEmailVerified()).isFalse();
        assertThat(user.getEmailVerifiedAt()).isNull();
        assertThat(user.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(user.getUpdatedAt()).isEqualTo(CREATED_AT);
    }



    @Test
    @ResourceLock(Resources.LOCALE)
    void normalizesEmailIndependentlyOfTheDefaultLocale() {
        Locale originalLocale = Locale.getDefault();
        Locale originalDisplayLocale = Locale.getDefault(Locale.Category.DISPLAY);
        Locale originalFormatLocale = Locale.getDefault(Locale.Category.FORMAT);

        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertThat(AppUser.normalizeEmail(" IOT.USER@EXAMPLE.COM ")).isEqualTo("iot.user@example.com");
            assertThat(new AppUser(" IOT.USER@EXAMPLE.COM ","hash",CREATED_AT).getEmail())
                    .isEqualTo("iot.user@example.com");
        } finally {
            Locale.setDefault(originalLocale);
            Locale.setDefault(Locale.Category.DISPLAY,originalDisplayLocale);
            Locale.setDefault(Locale.Category.FORMAT,originalFormatLocale);
        }
    }



    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "," \t\n "})
    void rejectsMissingOrBlankEmail(String email) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AppUser.normalizeEmail(email))
                .withMessage("email must not be blank");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AppUser(email,"hash",CREATED_AT))
                .withMessage("email must not be blank");
    }



    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "," \t\n "})
    void rejectsMissingOrBlankPasswordHashOnCreationAndUpdate(String passwordHash) {
        AppUser user = user();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AppUser("owner@example.com",passwordHash,CREATED_AT))
                .withMessage("passwordHash must not be blank");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> user.updatePasswordHash(passwordHash,UPDATED_AT))
                .withMessage("passwordHash must not be blank");

        assertThat(user.getPasswordHash()).isEqualTo("test-password-hash");
        assertThat(user.getUpdatedAt()).isEqualTo(CREATED_AT);
    }



    @Test
    void rejectsMissingCreationTimestamp() {
        assertThatNullPointerException()
                .isThrownBy(() -> new AppUser("owner@example.com","hash",null))
                .withMessage("createdAt must not be null");
    }



    @Test
    void createsUserWithExplicitPreferencesAndTrimmedTimezone() {
        AppUser user = new AppUser("owner@example.com","hash",PreferredLanguage.TURKISH,TemperatureUnit.FAHRENHEIT," Europe/Istanbul ",CREATED_AT);

        assertThat(user.getPreferredLanguage()).isEqualTo(PreferredLanguage.TURKISH);
        assertThat(user.getPreferredTemperatureUnit()).isEqualTo(TemperatureUnit.FAHRENHEIT);
        assertThat(user.getPreferredTimezone()).isEqualTo("Europe/Istanbul");
    }



    @Test
    void defaultsNullPreferencesDuringConstruction() {
        AppUser user = new AppUser("owner@example.com","hash",null,null,null,CREATED_AT);

        assertThat(user.getPreferredLanguage()).isEqualTo(PreferredLanguage.ENGLISH);
        assertThat(user.getPreferredTemperatureUnit()).isEqualTo(TemperatureUnit.CELSIUS);
        assertThat(user.getPreferredTimezone()).isEqualTo("UTC");
    }



    @Test
    void updatesPreferencesAndModificationTimestamp() {
        AppUser user = user();

        user.updatePreferences(PreferredLanguage.TURKISH,TemperatureUnit.KELVIN," Asia/Kathmandu ",UPDATED_AT);

        assertThat(user.getPreferredLanguage()).isEqualTo(PreferredLanguage.TURKISH);
        assertThat(user.getPreferredTemperatureUnit()).isEqualTo(TemperatureUnit.KELVIN);
        assertThat(user.getPreferredTimezone()).isEqualTo("Asia/Kathmandu");
        assertThat(user.getUpdatedAt()).isEqualTo(UPDATED_AT);
        assertThat(user.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(user.getEmail()).isEqualTo("owner@example.com");
        assertThat(user.getPasswordHash()).isEqualTo("test-password-hash");
    }



    @Test
    void resetsNullPreferencesToDefaultsDuringUpdate() {
        AppUser user = new AppUser("owner@example.com","hash",PreferredLanguage.TURKISH,TemperatureUnit.FAHRENHEIT,"Europe/Istanbul",CREATED_AT);

        user.updatePreferences(null,null,null,UPDATED_AT);

        assertThat(user.getPreferredLanguage()).isEqualTo(PreferredLanguage.ENGLISH);
        assertThat(user.getPreferredTemperatureUnit()).isEqualTo(TemperatureUnit.CELSIUS);
        assertThat(user.getPreferredTimezone()).isEqualTo("UTC");
        assertThat(user.getUpdatedAt()).isEqualTo(UPDATED_AT);
    }



    @ParameterizedTest
    @ValueSource(strings = {""," "," \t\n "})
    void rejectsBlankTimezonesOnCreationAndPreferenceUpdate(String timezone) {
        AppUser user = user();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AppUser("owner@example.com","hash",null,null,timezone,CREATED_AT))
                .withMessage("preferredTimezone must not be blank");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> user.updatePreferences(null,null,timezone,UPDATED_AT))
                .withMessage("preferredTimezone must not be blank");
    }



    @Test
    void rejectsUnknownTimezonesOnCreationAndPreferenceUpdate() {
        AppUser user = user();

        assertThatExceptionOfType(DateTimeException.class)
                .isThrownBy(() -> new AppUser("owner@example.com","hash",null,null,"Mars/Olympus_Mons",CREATED_AT));

        assertThatExceptionOfType(DateTimeException.class)
                .isThrownBy(() -> user.updatePreferences(null,null,"Mars/Olympus_Mons",UPDATED_AT));
    }



    @Test
    void updatesAndTrimsPasswordHash() {
        AppUser user = user();

        user.updatePasswordHash(" replacement-hash ",UPDATED_AT);

        assertThat(user.getPasswordHash()).isEqualTo("replacement-hash");
        assertThat(user.getUpdatedAt()).isEqualTo(UPDATED_AT);
        assertThat(user.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(user.getEmail()).isEqualTo("owner@example.com");
    }



    @Test
    void DisablesAndEnablesUserWithSuppliedTimestamps() {
        AppUser user = user();
        Instant enabledAt = UPDATED_AT.plusSeconds(60);

        user.disable(UPDATED_AT);

        assertThat(user.isEnabled()).isFalse();
        assertThat(user.getUpdatedAt()).isEqualTo(UPDATED_AT);

        user.enable(enabledAt);

        assertThat(user.isEnabled()).isTrue();
        assertThat(user.getUpdatedAt()).isEqualTo(enabledAt);
        assertThat(user.getCreatedAt()).isEqualTo(CREATED_AT);
    }



    @ParameterizedTest
    @ValueSource(longs = {0,1})
    void verifiesEmailAtOrAfterCreation(long elapsedSeconds) {
        AppUser user = user();
        Instant verifiedAt = CREATED_AT.plusSeconds(elapsedSeconds);

        user.verifyEmail(verifiedAt);

        assertThat(user.isEmailVerified()).isTrue();
        assertThat(user.getEmailVerifiedAt()).isEqualTo(verifiedAt);
        assertThat(user.getUpdatedAt()).isEqualTo(verifiedAt);
        assertThat(user.getCreatedAt()).isEqualTo(CREATED_AT);
    }



    @Test
    void rejectsMissingEmailVerificationTimestamp() {
        AppUser user = user();

        assertThatNullPointerException()
                .isThrownBy(() -> user.verifyEmail(null))
                .withMessage("verifiedAt must not be null");

        assertThat(user.isEmailVerified()).isFalse();
        assertThat(user.getUpdatedAt()).isEqualTo(CREATED_AT);
    }



    @Test
    void rejectsEmailVerificationBeforeCreation() {
        AppUser user = user();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> user.verifyEmail(CREATED_AT.minusNanos(1)))
                .withMessage("verifiedAt must not be before createdAt");

        assertThat(user.isEmailVerified()).isFalse();
        assertThat(user.getEmailVerifiedAt()).isNull();
        assertThat(user.getUpdatedAt()).isEqualTo(CREATED_AT);
    }



    @Test
    void repeatedVerificationPreservesOriginalVerificationAndLatestUpdate() {
        AppUser user = user();
        Instant verifiedAt = CREATED_AT.plusSeconds(10);

        user.verifyEmail(verifiedAt);
        user.updatePasswordHash("replacement-hash",UPDATED_AT);
        user.verifyEmail(UPDATED_AT.plusSeconds(60));

        assertThat(user.getEmailVerifiedAt()).isEqualTo(verifiedAt);
        assertThat(user.getUpdatedAt()).isEqualTo(UPDATED_AT);
        assertThat(user.getPasswordHash()).isEqualTo("replacement-hash");
    }



    @ParameterizedTest
    @ValueSource(longs = {0,1})
    void persistenceCallbacksAcceptUpdateAtOrAfterCreation(long elapsedSeconds) {
        AppUser user = user();
        user.updatePasswordHash("replacement-hash",CREATED_AT.plusSeconds(elapsedSeconds));

        assertThatNoException().isThrownBy(user::prePersist);
        assertThatNoException().isThrownBy(user::preUpdate);
    }



    @ParameterizedTest
    @MethodSource("invalidUpdateTimestamps")
    void persistenceCallbacksRejectInvalidUpdateTimestamps(Instant updatedAt,String expectedMessage) {
        AppUser user = user();
        user.updatePasswordHash("replacement-hash",updatedAt);

        assertThatIllegalStateException().isThrownBy(user::prePersist).withMessage(expectedMessage);
        assertThatIllegalStateException().isThrownBy(user::preUpdate).withMessage(expectedMessage);
    }



    @Test
    void persistenceCallbacksRejectUninitializedTimestamps() {
        AppUser user = new AppUser();

        assertThatIllegalStateException()
                .isThrownBy(user::prePersist)
                .withMessage("createdAt and updatedAt must not be null");

        assertThatIllegalStateException()
                .isThrownBy(user::preUpdate)
                .withMessage("createdAt and updatedAt must not be null");
    }



    private static Stream<Arguments> invalidUpdateTimestamps() {
        return Stream.of(
                Arguments.of(null,"createdAt and updatedAt must not be null"),
                Arguments.of(CREATED_AT.minusNanos(1),"updatedAt must not be before createdAt")
        );
    }
}