package com.enginertugrul.iotsensormonitor.service.user.recovery;

import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.entity.user.PasswordResetChallenge;
import com.enginertugrul.iotsensormonitor.entity.user.PreferredLanguage;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.repository.AppUserRepository;
import com.enginertugrul.iotsensormonitor.repository.PasswordResetChallengeRepository;
import com.enginertugrul.iotsensormonitor.security.onetimecode.GeneratedOneTimeCode;
import com.enginertugrul.iotsensormonitor.security.recovery.PasswordRecoveryCodeGenerator;
import com.enginertugrul.iotsensormonitor.security.recovery.PasswordRecoveryPolicy;
import com.enginertugrul.iotsensormonitor.security.recovery.PasswordRecoveryRateLimiter;
import com.enginertugrul.iotsensormonitor.service.user.password.PasswordChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.stream.Stream;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;



@ExtendWith(MockitoExtension.class)
class PasswordRecoveryServiceImplTest {

    private static final Long USER_ID = 42L;
    private static final String EMAIL = "owner@example.com";
    private static final String CLIENT_KEY = "recovery-client";
    private static final String RAW_CODE = "12345678";
    private static final String OLD_CODE_HASH = "old-code-hash";
    private static final String OLD_PASSWORD_HASH = "old-password-hash";
    private static final String NEW_PASSWORD = "replacement-password";
    private static final String NEW_PASSWORD_HASH = "replacement-password-hash";
    private static final Instant NOW = CREATED_AT.plusSeconds(3600);
    private static final Duration CODE_LIFETIME = Duration.ofMinutes(15);
    private static final Duration RESEND_COOLDOWN = Duration.ofMinutes(1);
    private static final int MAXIMUM_FAILED_ATTEMPTS = 5;
    private static final GeneratedOneTimeCode GENERATED_CODE = new GeneratedOneTimeCode("87654321","new-code-hash");
    private static final PasswordRecoveryPolicy POLICY = new PasswordRecoveryPolicy(CODE_LIFETIME,RESEND_COOLDOWN,MAXIMUM_FAILED_ATTEMPTS,Duration.ofMinutes(15),5,20,Duration.ofMinutes(15),30,100);

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private PasswordResetChallengeRepository challengeRepository;

    @Mock
    private PasswordRecoveryCodeGenerator codeGenerator;

    @Mock
    private PasswordRecoveryRateLimiter rateLimiter;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private PasswordRecoveryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PasswordRecoveryServiceImpl(appUserRepository,challengeRepository,codeGenerator,POLICY,rateLimiter,passwordEncoder,eventPublisher,Clock.fixed(NOW,ZoneOffset.UTC));
    }



    @Test
    void issuesChallengeForNormalizedAddressWithPolicyTimestampsAndDeliverySnapshot() {
        AppUser user = user(true,true);
        stubRequest(user,null);
        when(codeGenerator.generate(USER_ID)).thenReturn(GENERATED_CODE);

        service.requestResetCode(" OWNER@Example.COM ",CLIENT_KEY);

        ArgumentCaptor<PasswordResetChallenge> captor = ArgumentCaptor.forClass(PasswordResetChallenge.class);
        verify(challengeRepository).save(captor.capture());
        PasswordResetChallenge challenge = captor.getValue();

        assertThat(challenge.getUser()).isSameAs(user);
        assertThat(challenge.getCodeHash()).isEqualTo(GENERATED_CODE.codeHash()).isNotEqualTo(GENERATED_CODE.rawCode());
        assertThat(challenge.getIssuedAt()).isEqualTo(NOW);
        assertThat(challenge.getExpiresAt()).isEqualTo(NOW.plus(CODE_LIFETIME));
        assertThat(challenge.getResendAvailableAt()).isEqualTo(NOW.plus(RESEND_COOLDOWN));
        assertThat(challenge.getFailedAttempts()).isZero();
        assertThat(challenge.getCreatedAt()).isEqualTo(NOW);
        assertThat(challenge.getUpdatedAt()).isEqualTo(NOW);
        assertThat(user.getPasswordHash()).isEqualTo(OLD_PASSWORD_HASH);
        assertDeliveryPublished();
        verifyNoInteractions(passwordEncoder);
    }



    @Test
    void stopsRateLimitedIssuanceBeforeRepositoryAccess() {
        when(rateLimiter.allowCodeIssue(EMAIL,CLIENT_KEY,NOW)).thenReturn(false);

        service.requestResetCode(" OWNER@Example.COM ",CLIENT_KEY);

        verify(rateLimiter).allowCodeIssue(EMAIL,CLIENT_KEY,NOW);
        verifyNoInteractions(appUserRepository,challengeRepository,codeGenerator,passwordEncoder,eventPublisher);
    }



    @ParameterizedTest
    @MethodSource("invalidEmails")
    void rejectsInvalidAddressesUniformlyAfterApplyingRateLimits(String email) {
        when(rateLimiter.allowCodeIssue("[invalid-address]",CLIENT_KEY,NOW)).thenReturn(true);
        when(rateLimiter.allowPasswordReset(CLIENT_KEY,NOW)).thenReturn(true);

        service.requestResetCode(email,CLIENT_KEY);

        assertThat(service.resetPassword(email,RAW_CODE,NEW_PASSWORD,CLIENT_KEY)).isEqualTo(PasswordRecoveryResult.INVALID);

        verify(rateLimiter).allowCodeIssue("[invalid-address]",CLIENT_KEY,NOW);
        verify(rateLimiter).allowPasswordReset(CLIENT_KEY,NOW);
        verifyNoInteractions(appUserRepository,challengeRepository,codeGenerator,passwordEncoder,eventPublisher);
    }



    @Test
    void silentlyIgnoresUnknownAddressAndReturnsUniformInvalidResetResult() {
        when(rateLimiter.allowCodeIssue(EMAIL,CLIENT_KEY,NOW)).thenReturn(true);
        when(rateLimiter.allowPasswordReset(CLIENT_KEY,NOW)).thenReturn(true);
        when(appUserRepository.findByEmailForUpdate(EMAIL)).thenReturn(Optional.empty());

        service.requestResetCode(" OWNER@Example.COM ",CLIENT_KEY);

        assertThat(service.resetPassword(" OWNER@Example.COM ",RAW_CODE,NEW_PASSWORD,CLIENT_KEY)).isEqualTo(PasswordRecoveryResult.INVALID);
        verifyNoInteractions(challengeRepository,codeGenerator,passwordEncoder,eventPublisher);
    }



    @ParameterizedTest
    @CsvSource({
            "false,true,true",
            "false,true,false",
            "true,false,true",
            "true,false,false",
            "false,false,true",
            "false,false,false"
    })
    void rejectsIneligibleUsersAndDeletesTheirStaleChallenges(boolean verified,boolean enabled,boolean request) {
        AppUser user = user(verified,enabled);
        PasswordResetChallenge challenge = challenge(user,NOW.minusSeconds(60));
        Instant originalUpdatedAt = user.getUpdatedAt();

        if (request) {
            stubRequest(user,challenge);
            service.requestResetCode(EMAIL,CLIENT_KEY);
        } else {
            stubReset(user,challenge);
            assertThat(service.resetPassword(EMAIL,RAW_CODE,NEW_PASSWORD,CLIENT_KEY)).isEqualTo(PasswordRecoveryResult.INVALID);
        }

        verify(challengeRepository).delete(challenge);
        assertThat(user.getPasswordHash()).isEqualTo(OLD_PASSWORD_HASH);
        assertThat(user.getUpdatedAt()).isEqualTo(originalUpdatedAt);
        assertThat(challenge.getFailedAttempts()).isZero();
        verifyNoInteractions(codeGenerator,passwordEncoder,eventPublisher);
    }



    @Test
    void preservesChallengeImmediatelyBeforeResendCooldownEnds() {
        AppUser user = user(true,true);
        Instant issuedAt = NOW.minus(RESEND_COOLDOWN).plusNanos(1);
        PasswordResetChallenge challenge = challenge(user,issuedAt);
        challenge.recordFailedAttempt(NOW.minusSeconds(1));
        stubRequest(user,challenge);

        service.requestResetCode(EMAIL,CLIENT_KEY);

        assertThat(challenge.getCodeHash()).isEqualTo(OLD_CODE_HASH);
        assertThat(challenge.getIssuedAt()).isEqualTo(issuedAt);
        assertThat(challenge.getFailedAttempts()).isEqualTo(1);
        assertThat(challenge.getUpdatedAt()).isEqualTo(NOW.minusSeconds(1));
        verify(challengeRepository,never()).save(any(PasswordResetChallenge.class));
        verifyNoInteractions(codeGenerator,passwordEncoder,eventPublisher);
    }



    @ParameterizedTest
    @ValueSource(longs = {60,900})
    void rotatesExhaustedChallengeAtResendBoundaryOrExpiry(long secondsSinceIssue) {
        AppUser user = user(true,true);
        Instant issuedAt = NOW.minusSeconds(secondsSinceIssue);
        PasswordResetChallenge challenge = challenge(user,issuedAt);
        exhaust(challenge);
        stubRequest(user,challenge);
        when(codeGenerator.generate(USER_ID)).thenReturn(GENERATED_CODE);

        service.requestResetCode(EMAIL,CLIENT_KEY);

        assertThat(challenge.getCodeHash()).isEqualTo(GENERATED_CODE.codeHash());
        assertThat(challenge.getIssuedAt()).isEqualTo(NOW);
        assertThat(challenge.getExpiresAt()).isEqualTo(NOW.plus(CODE_LIFETIME));
        assertThat(challenge.getResendAvailableAt()).isEqualTo(NOW.plus(RESEND_COOLDOWN));
        assertThat(challenge.getFailedAttempts()).isZero();
        assertThat(challenge.getCreatedAt()).isEqualTo(issuedAt);
        assertThat(challenge.getUpdatedAt()).isEqualTo(NOW);
        verify(challengeRepository,never()).save(any(PasswordResetChallenge.class));
        assertDeliveryPublished();
        verifyNoInteractions(passwordEncoder);
    }



    @Test
    void rejectsRateLimitedResetBeforeRepositoryAccess() {
        when(rateLimiter.allowPasswordReset(CLIENT_KEY,NOW)).thenReturn(false);

        assertThat(service.resetPassword(EMAIL,RAW_CODE,NEW_PASSWORD,CLIENT_KEY)).isEqualTo(PasswordRecoveryResult.INVALID);

        verify(rateLimiter).allowPasswordReset(CLIENT_KEY,NOW);
        verifyNoInteractions(appUserRepository,challengeRepository,codeGenerator,passwordEncoder,eventPublisher);
    }



    @ParameterizedTest
    @MethodSource("invalidPasswords")
    void rejectsInvalidNewPasswordsBeforeRepositoryAccess(String password) {
        when(rateLimiter.allowPasswordReset(CLIENT_KEY,NOW)).thenReturn(true);

        assertThat(service.resetPassword(EMAIL,RAW_CODE,password,CLIENT_KEY)).isEqualTo(PasswordRecoveryResult.INVALID);

        verify(rateLimiter).allowPasswordReset(CLIENT_KEY,NOW);
        verifyNoInteractions(appUserRepository,challengeRepository,codeGenerator,passwordEncoder,eventPublisher);
    }



    @Test
    void rejectsResetWithoutChallenge() {
        AppUser user = user(true,true);
        stubReset(user,null);

        assertThat(service.resetPassword(EMAIL,RAW_CODE,NEW_PASSWORD,CLIENT_KEY)).isEqualTo(PasswordRecoveryResult.INVALID);

        assertThat(user.getPasswordHash()).isEqualTo(OLD_PASSWORD_HASH);
        verifyNoInteractions(codeGenerator,passwordEncoder,eventPublisher);
    }



    @ParameterizedTest
    @ValueSource(longs = {0,1})
    void rejectsCorrectCodeAtOrAfterExpiryWithoutRecordingAnotherAttempt(long secondsAfterExpiry) {
        AppUser user = user(true,true);
        PasswordResetChallenge challenge = challenge(user,NOW.minus(CODE_LIFETIME).minusSeconds(secondsAfterExpiry));
        stubReset(user,challenge);

        assertThat(service.resetPassword(EMAIL,RAW_CODE,NEW_PASSWORD,CLIENT_KEY)).isEqualTo(PasswordRecoveryResult.INVALID);

        assertThat(challenge.getFailedAttempts()).isZero();
        assertThat(challenge.getUpdatedAt()).isEqualTo(challenge.getIssuedAt());
        assertThat(user.getPasswordHash()).isEqualTo(OLD_PASSWORD_HASH);
        verify(challengeRepository,never()).delete(any(PasswordResetChallenge.class));
        verifyNoInteractions(codeGenerator,passwordEncoder,eventPublisher);
    }



    @Test
    void rejectsResetAfterAttemptLimitWithoutMatchingOrIncrementingAgain() {
        AppUser user = user(true,true);
        PasswordResetChallenge challenge = challenge(user,NOW.minusSeconds(60));
        exhaust(challenge);
        stubReset(user,challenge);

        assertThat(service.resetPassword(EMAIL,RAW_CODE,NEW_PASSWORD,CLIENT_KEY)).isEqualTo(PasswordRecoveryResult.INVALID);

        assertThat(challenge.getFailedAttempts()).isEqualTo(MAXIMUM_FAILED_ATTEMPTS);
        assertThat(challenge.getUpdatedAt()).isEqualTo(NOW.minusSeconds(1));
        assertThat(user.getPasswordHash()).isEqualTo(OLD_PASSWORD_HASH);
        verifyNoInteractions(codeGenerator,passwordEncoder,eventPublisher);
    }



    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ","1234567","abcdefgh","99999999"})
    void recordsFailedAttemptForNonmatchingCodeWithoutChangingPassword(String rawCode) {
        AppUser user = user(true,true);
        PasswordResetChallenge challenge = challenge(user,NOW.minusSeconds(60));
        Instant originalUpdatedAt = user.getUpdatedAt();
        stubReset(user,challenge);
        when(codeGenerator.matches(USER_ID,rawCode,OLD_CODE_HASH)).thenReturn(false);

        assertThat(service.resetPassword(" OWNER@Example.COM ",rawCode,NEW_PASSWORD,CLIENT_KEY)).isEqualTo(PasswordRecoveryResult.INVALID);

        assertThat(challenge.getFailedAttempts()).isEqualTo(1);
        assertThat(challenge.getUpdatedAt()).isEqualTo(NOW);
        assertThat(challenge.getCodeHash()).isEqualTo(OLD_CODE_HASH);
        assertThat(user.getPasswordHash()).isEqualTo(OLD_PASSWORD_HASH);
        assertThat(user.getUpdatedAt()).isEqualTo(originalUpdatedAt);
        verify(codeGenerator).matches(USER_ID,rawCode,OLD_CODE_HASH);
        verify(challengeRepository,never()).delete(any(PasswordResetChallenge.class));
        verifyNoInteractions(passwordEncoder,eventPublisher);
    }



    @ParameterizedTest
    @MethodSource("validPasswords")
    void replacesPasswordOnLastAllowedAttemptImmediatelyBeforeExpiry(String password) {
        AppUser user = user(true,true);
        PasswordResetChallenge challenge = new PasswordResetChallenge(user,OLD_CODE_HASH,NOW.minusSeconds(120),NOW.plusNanos(1),NOW.minusSeconds(60));
        for (int attempt = 0; attempt < MAXIMUM_FAILED_ATTEMPTS - 1; attempt++) {
            challenge.recordFailedAttempt(NOW.minusSeconds(1));
        }
        Instant verifiedAt = user.getEmailVerifiedAt();
        stubReset(user,challenge);
        when(codeGenerator.matches(USER_ID,RAW_CODE,OLD_CODE_HASH)).thenReturn(true);
        when(passwordEncoder.encode(password)).thenReturn(NEW_PASSWORD_HASH);

        assertThat(service.resetPassword(" OWNER@Example.COM ",RAW_CODE,password,CLIENT_KEY)).isEqualTo(PasswordRecoveryResult.PASSWORD_RESET);

        assertThat(user.getPasswordHash()).isEqualTo(NEW_PASSWORD_HASH).isNotEqualTo(password);
        assertThat(user.getUpdatedAt()).isEqualTo(NOW);
        assertThat(user.getEmailVerifiedAt()).isEqualTo(verifiedAt);
        assertThat(challenge.getFailedAttempts()).isEqualTo(MAXIMUM_FAILED_ATTEMPTS - 1);
        verify(passwordEncoder).encode(password);
        verify(challengeRepository).delete(challenge);
        verify(eventPublisher).publishEvent(new PasswordChangedEvent(USER_ID));
    }



    @Test
    void preservesPasswordAndChallengeWhenEncodingFails() {
        AppUser user = user(true,true);
        PasswordResetChallenge challenge = challenge(user,NOW.minusSeconds(60));
        Instant originalUpdatedAt = user.getUpdatedAt();
        IllegalStateException failure = new IllegalStateException("Password encoding failed");
        stubReset(user,challenge);
        when(codeGenerator.matches(USER_ID,RAW_CODE,OLD_CODE_HASH)).thenReturn(true);
        when(passwordEncoder.encode(NEW_PASSWORD)).thenThrow(failure);

        assertThatThrownBy(() -> service.resetPassword(EMAIL,RAW_CODE,NEW_PASSWORD,CLIENT_KEY)).isSameAs(failure);

        assertThat(user.getPasswordHash()).isEqualTo(OLD_PASSWORD_HASH);
        assertThat(user.getUpdatedAt()).isEqualTo(originalUpdatedAt);
        assertThat(challenge.getFailedAttempts()).isZero();
        verify(challengeRepository,never()).delete(any(PasswordResetChallenge.class));
        verifyNoInteractions(eventPublisher);
    }



    @Test
    void rejectsDeliveryForMissingUser() {
        when(appUserRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.empty());

        assertThat(service.canDeliverCode(delivery())).isFalse();

        verifyNoInteractions(challengeRepository,codeGenerator,rateLimiter,passwordEncoder,eventPublisher);
    }



    @ParameterizedTest
    @CsvSource({"false,true","true,false","false,false"})
    void rejectsDeliveryForIneligibleUserWithoutAccessingChallenge(boolean verified,boolean enabled) {
        when(appUserRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user(verified,enabled)));

        assertThat(service.canDeliverCode(delivery())).isFalse();

        verifyNoInteractions(challengeRepository,codeGenerator,rateLimiter,passwordEncoder,eventPublisher);
    }



    @Test
    void rejectsDeliveryWithoutCurrentChallenge() {
        stubDelivery(user(true,true),null);

        assertThat(service.canDeliverCode(delivery())).isFalse();

        verifyNoInteractions(codeGenerator,rateLimiter,passwordEncoder,eventPublisher);
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void rejectsDeliveryForExpiredOrExhaustedChallenge(boolean expired) {
        AppUser user = user(true,true);
        PasswordResetChallenge challenge = challenge(user,expired ? NOW.minus(CODE_LIFETIME) : NOW.minusSeconds(60));
        if (!expired) {
            exhaust(challenge);
        }
        stubDelivery(user,challenge);

        assertThat(service.canDeliverCode(delivery())).isFalse();

        verifyNoInteractions(codeGenerator,rateLimiter,passwordEncoder,eventPublisher);
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void allowsDeliveryOnlyWhenCodeMatchesCurrentChallengeWithoutMutatingAttempts(boolean matches) {
        AppUser user = user(true,true);
        PasswordResetChallenge challenge = challenge(user,NOW.minusSeconds(60));
        challenge.recordFailedAttempt(NOW.minusSeconds(1));
        stubDelivery(user,challenge);
        when(codeGenerator.matches(USER_ID,RAW_CODE,OLD_CODE_HASH)).thenReturn(matches);

        assertThat(service.canDeliverCode(delivery())).isEqualTo(matches);

        assertThat(challenge.getFailedAttempts()).isEqualTo(1);
        assertThat(challenge.getUpdatedAt()).isEqualTo(NOW.minusSeconds(1));
        verify(challengeRepository,never()).delete(any(PasswordResetChallenge.class));
        verifyNoInteractions(rateLimiter,passwordEncoder,eventPublisher);
    }



    private void stubRequest(AppUser user,PasswordResetChallenge challenge) {
        when(rateLimiter.allowCodeIssue(EMAIL,CLIENT_KEY,NOW)).thenReturn(true);
        when(appUserRepository.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(user));
        when(challengeRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.ofNullable(challenge));
    }



    private void stubReset(AppUser user,PasswordResetChallenge challenge) {
        when(rateLimiter.allowPasswordReset(CLIENT_KEY,NOW)).thenReturn(true);
        when(appUserRepository.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(user));
        when(challengeRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.ofNullable(challenge));
    }



    private void stubDelivery(AppUser user,PasswordResetChallenge challenge) {
        when(appUserRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
        when(challengeRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.ofNullable(challenge));
    }



    private void assertDeliveryPublished() {
        ArgumentCaptor<PasswordRecoveryCodeDelivery> captor = ArgumentCaptor.forClass(PasswordRecoveryCodeDelivery.class);
        verify(eventPublisher).publishEvent(captor.capture());
        PasswordRecoveryCodeDelivery delivery = captor.getValue();

        assertThat(delivery.userId()).isEqualTo(USER_ID);
        assertThat(delivery.recipientEmail()).isEqualTo(EMAIL);
        assertThat(delivery.preferredLanguage()).isEqualTo(PreferredLanguage.TURKISH);
        assertThat(delivery.rawCode()).isEqualTo(GENERATED_CODE.rawCode());
        assertThat(delivery.expiresAt()).isEqualTo(NOW.plus(CODE_LIFETIME));
    }



    private static AppUser user(boolean verified,boolean enabled) {
        AppUser user = new AppUser(EMAIL,OLD_PASSWORD_HASH,PreferredLanguage.TURKISH,TemperatureUnit.CELSIUS,"UTC",CREATED_AT);
        ReflectionTestUtils.setField(user,"id",USER_ID);
        if (verified) {
            user.verifyEmail(CREATED_AT.plusSeconds(1));
        }
        if (!enabled) {
            user.disable(CREATED_AT.plusSeconds(2));
        }
        return user;
    }



    private static PasswordResetChallenge challenge(AppUser user,Instant issuedAt) {
        return new PasswordResetChallenge(user,OLD_CODE_HASH,issuedAt,issuedAt.plus(CODE_LIFETIME),issuedAt.plus(RESEND_COOLDOWN));
    }



    private static PasswordRecoveryCodeDelivery delivery() {
        return new PasswordRecoveryCodeDelivery(USER_ID,EMAIL,PreferredLanguage.TURKISH,RAW_CODE,NOW.plus(CODE_LIFETIME));
    }



    private static void exhaust(PasswordResetChallenge challenge) {
        for (int attempt = 0; attempt < MAXIMUM_FAILED_ATTEMPTS; attempt++) {
            challenge.recordFailedAttempt(NOW.minusSeconds(1));
        }
    }



    private static Stream<String> invalidEmails() {
        return Stream.of(null,""," \t\n ","a".repeat(321));
    }



    private static Stream<String> invalidPasswords() {
        return Stream.of(null,""," ".repeat(8),"a".repeat(7),"a".repeat(73));
    }



    private static Stream<String> validPasswords() {
        return Stream.of("a".repeat(8),"a".repeat(72),"  replacement-password  ");
    }
}