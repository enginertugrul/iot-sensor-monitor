package com.enginertugrul.iotsensormonitor.service.user.password;

import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.entity.user.PasswordResetChallenge;
import com.enginertugrul.iotsensormonitor.entity.user.PreferredLanguage;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.repository.AppUserRepository;
import com.enginertugrul.iotsensormonitor.repository.PasswordResetChallengeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Stream;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;



@ExtendWith(MockitoExtension.class)
class PasswordChangeServiceImplTest {

    private static final Long USER_ID = 42L;
    private static final String CURRENT_PASSWORD = "current-password";
    private static final String NEW_PASSWORD = "replacement-password";
    private static final String OLD_HASH = "stored-password-hash";
    private static final String NEW_HASH = "replacement-password-hash";
    private static final Instant ORIGINAL_UPDATED_AT = CREATED_AT.plusSeconds(60);
    private static final Instant NOW = CREATED_AT.plusSeconds(3600);

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private PasswordResetChallengeRepository challengeRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private PasswordChangeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PasswordChangeServiceImpl(appUserRepository,challengeRepository,passwordEncoder,eventPublisher,Clock.fixed(NOW,ZoneOffset.UTC));
    }



    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0,-1})
    void rejectsInvalidUserIdBeforePasswordValidationOrRepositoryAccess(Long userId) {
        assertThatThrownBy(() -> service.changePassword(userId,null,null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("userId must be positive");

        verifyNoInteractions(appUserRepository,challengeRepository,passwordEncoder,eventPublisher);
    }



    @ParameterizedTest
    @MethodSource("invalidPasswords")
    void rejectsInvalidCurrentPasswordBeforeRepositoryAccess(String currentPassword) {
        assertThat(service.changePassword(USER_ID,currentPassword,NEW_PASSWORD)).isEqualTo(PasswordChangeResult.CURRENT_PASSWORD_INVALID);

        verifyNoInteractions(appUserRepository,challengeRepository,passwordEncoder,eventPublisher);
    }



    @ParameterizedTest
    @MethodSource("invalidPasswords")
    void rejectsInvalidNewPasswordBeforeRepositoryAccess(String newPassword) {
        assertThat(service.changePassword(USER_ID,CURRENT_PASSWORD,newPassword)).isEqualTo(PasswordChangeResult.NEW_PASSWORD_INVALID);

        verifyNoInteractions(appUserRepository,challengeRepository,passwordEncoder,eventPublisher);
    }



    @Test
    void reportsCurrentPasswordInvalidWhenBothPasswordsAreInvalid() {
        assertThat(service.changePassword(USER_ID,null,null)).isEqualTo(PasswordChangeResult.CURRENT_PASSWORD_INVALID);

        verifyNoInteractions(appUserRepository,challengeRepository,passwordEncoder,eventPublisher);
    }



    @Test
    void rejectsMissingUser() {
        when(appUserRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changePassword(USER_ID,CURRENT_PASSWORD,NEW_PASSWORD))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage("User not found");

        verifyNoInteractions(challengeRepository,passwordEncoder,eventPublisher);
    }



    @Test
    void rejectsIncorrectCurrentPasswordWithoutChangingUserOrLookingUpChallenge() {
        AppUser user = user();
        when(appUserRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(CURRENT_PASSWORD,OLD_HASH)).thenReturn(false);

        assertThat(service.changePassword(USER_ID,CURRENT_PASSWORD,NEW_PASSWORD)).isEqualTo(PasswordChangeResult.CURRENT_PASSWORD_INVALID);

        assertUnchanged(user);
        verify(passwordEncoder,never()).matches(NEW_PASSWORD,OLD_HASH);
        verify(passwordEncoder,never()).encode(anyString());
        verifyNoInteractions(challengeRepository,eventPublisher);
    }



    @Test
    void rejectsNewPasswordThatMatchesStoredHash() {
        AppUser user = user();
        when(appUserRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(CURRENT_PASSWORD,OLD_HASH)).thenReturn(true);
        when(passwordEncoder.matches(NEW_PASSWORD,OLD_HASH)).thenReturn(true);

        assertThat(service.changePassword(USER_ID,CURRENT_PASSWORD,NEW_PASSWORD)).isEqualTo(PasswordChangeResult.NEW_PASSWORD_UNCHANGED);

        assertUnchanged(user);
        verify(passwordEncoder,never()).encode(anyString());
        verifyNoInteractions(challengeRepository,eventPublisher);
    }



    @ParameterizedTest
    @MethodSource("validPasswordChanges")
    void replacesPasswordAndPublishesEventWithOrWithoutResetChallenge(String currentPassword,String newPassword,boolean hasChallenge) {
        AppUser user = user();
        PasswordResetChallenge challenge = hasChallenge ? challenge(user) : null;
        stubAcceptedPasswords(user,currentPassword,newPassword);
        when(challengeRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.ofNullable(challenge));
        when(passwordEncoder.encode(newPassword)).thenReturn(NEW_HASH);

        assertThat(service.changePassword(USER_ID,currentPassword,newPassword)).isEqualTo(PasswordChangeResult.PASSWORD_CHANGED);

        assertThat(user.getPasswordHash()).isEqualTo(NEW_HASH).isNotEqualTo(newPassword);
        assertThat(user.getUpdatedAt()).isEqualTo(NOW);
        assertThat(user.getEmailVerifiedAt()).isEqualTo(ORIGINAL_UPDATED_AT);
        assertThat(user.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(user.getEmail()).isEqualTo("owner@example.com");
        assertThat(user.getPreferredLanguage()).isEqualTo(PreferredLanguage.TURKISH);
        assertThat(user.getPreferredTemperatureUnit()).isEqualTo(TemperatureUnit.FAHRENHEIT);
        assertThat(user.getPreferredTimezone()).isEqualTo("Europe/Istanbul");
        assertThat(user.isEnabled()).isTrue();

        verify(passwordEncoder).matches(currentPassword,OLD_HASH);
        verify(passwordEncoder).matches(newPassword,OLD_HASH);
        verify(passwordEncoder).encode(newPassword);
        verify(eventPublisher).publishEvent(new PasswordChangedEvent(USER_ID));

        if (hasChallenge) {
            verify(challengeRepository).delete(challenge);
        } else {
            verify(challengeRepository,never()).delete(any(PasswordResetChallenge.class));
        }
    }



    @Test
    void propagatesEncodingFailureWithoutChangingUserOrPublishingEvent() {
        AppUser user = user();
        IllegalStateException failure = new IllegalStateException("Password encoding failed");
        stubAcceptedPasswords(user,CURRENT_PASSWORD,NEW_PASSWORD);
        when(challengeRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.empty());
        when(passwordEncoder.encode(NEW_PASSWORD)).thenThrow(failure);

        assertThatThrownBy(() -> service.changePassword(USER_ID,CURRENT_PASSWORD,NEW_PASSWORD)).isSameAs(failure);

        assertUnchanged(user);
        verifyNoInteractions(eventPublisher);
    }



    private void stubAcceptedPasswords(AppUser user,String currentPassword,String newPassword) {
        when(appUserRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(currentPassword,OLD_HASH)).thenReturn(true);
        when(passwordEncoder.matches(newPassword,OLD_HASH)).thenReturn(false);
    }



    private static void assertUnchanged(AppUser user) {
        assertThat(user.getPasswordHash()).isEqualTo(OLD_HASH);
        assertThat(user.getUpdatedAt()).isEqualTo(ORIGINAL_UPDATED_AT);
    }



    private static AppUser user() {
        AppUser user = new AppUser("owner@example.com",OLD_HASH,PreferredLanguage.TURKISH,TemperatureUnit.FAHRENHEIT,"Europe/Istanbul",CREATED_AT);
        ReflectionTestUtils.setField(user,"id",USER_ID);
        user.verifyEmail(ORIGINAL_UPDATED_AT);
        return user;
    }



    private static PasswordResetChallenge challenge(AppUser user) {
        return new PasswordResetChallenge(user,"reset-code-hash",NOW.minusSeconds(60),NOW.plusSeconds(600),NOW);
    }



    private static Stream<String> invalidPasswords() {
        return Stream.of(null,""," ".repeat(8)," \t\n ".repeat(3),"a".repeat(7),"a".repeat(73));
    }



    private static Stream<Arguments> validPasswordChanges() {
        return Stream.of(
                Arguments.of("c".repeat(8),"n".repeat(72),true),
                Arguments.of("c".repeat(72),"n".repeat(8),false),
                Arguments.of("  current-password  ","  replacement-password  ",true));
    }
}