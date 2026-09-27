package com.enginertugrul.iotsensormonitor.service.user.verification;

import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.entity.user.EmailVerificationChallenge;
import com.enginertugrul.iotsensormonitor.entity.user.PreferredLanguage;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.exception.EmailVerificationUserNotFoundException;
import com.enginertugrul.iotsensormonitor.repository.AppUserRepository;
import com.enginertugrul.iotsensormonitor.repository.EmailVerificationChallengeRepository;
import com.enginertugrul.iotsensormonitor.security.onetimecode.GeneratedOneTimeCode;
import com.enginertugrul.iotsensormonitor.security.verification.EmailVerificationCodeGenerator;
import com.enginertugrul.iotsensormonitor.security.verification.EmailVerificationPolicy;
import com.enginertugrul.iotsensormonitor.security.verification.EmailVerificationRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;



@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceImplTest {

    private static final Long USER_ID = 42L;
    private static final String EMAIL = "owner@example.com";
    private static final String CLIENT_KEY = "verification-client";
    private static final String RAW_CODE = "12345678";
    private static final String OLD_HASH = "old-code-hash";
    private static final Instant NOW = CREATED_AT.plusSeconds(3600);
    private static final Duration CODE_LIFETIME = Duration.ofMinutes(15);
    private static final Duration RESEND_COOLDOWN = Duration.ofMinutes(1);
    private static final int MAXIMUM_FAILED_ATTEMPTS = 5;
    private static final GeneratedOneTimeCode GENERATED_CODE = new GeneratedOneTimeCode("87654321","new-code-hash");
    private static final EmailVerificationPolicy POLICY = new EmailVerificationPolicy(CODE_LIFETIME,RESEND_COOLDOWN,MAXIMUM_FAILED_ATTEMPTS,Duration.ofMinutes(15),5,20,Duration.ofMinutes(15),30,100);


    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private EmailVerificationChallengeRepository challengeRepository;

    @Mock
    private EmailVerificationCodeGenerator codeGenerator;

    @Mock
    private EmailVerificationRateLimiter rateLimiter;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private EmailVerificationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new EmailVerificationServiceImpl(appUserRepository,challengeRepository,codeGenerator,POLICY,rateLimiter,eventPublisher,Clock.fixed(NOW,ZoneOffset.UTC));
    }



    @Test
    void issuesInitialChallengeWithPolicyTimestampsAndDeliverySnapshot() {
        AppUser user = user();
        when(appUserRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
        when(challengeRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.empty());
        when(codeGenerator.generate(USER_ID)).thenReturn(GENERATED_CODE);

        service.issueInitialCode(USER_ID);

        ArgumentCaptor<EmailVerificationChallenge> challengeCaptor = ArgumentCaptor.forClass(EmailVerificationChallenge.class);
        verify(challengeRepository).save(challengeCaptor.capture());
        EmailVerificationChallenge challenge = challengeCaptor.getValue();

        assertThat(challenge.getUser()).isSameAs(user);
        assertThat(challenge.getCodeHash()).isEqualTo(GENERATED_CODE.codeHash()).isNotEqualTo(GENERATED_CODE.rawCode());
        assertThat(challenge.getIssuedAt()).isEqualTo(NOW);
        assertThat(challenge.getExpiresAt()).isEqualTo(NOW.plus(CODE_LIFETIME));
        assertThat(challenge.getResendAvailableAt()).isEqualTo(NOW.plus(RESEND_COOLDOWN));
        assertThat(challenge.getFailedAttempts()).isZero();
        assertThat(challenge.getCreatedAt()).isEqualTo(NOW);
        assertThat(challenge.getUpdatedAt()).isEqualTo(NOW);
        assertDeliveryPublished();
        verifyNoInteractions(rateLimiter);
    }



    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0,-1})
    void rejectsInvalidInitialUserIdsBeforeAccessingCollaborators(Long userId) {
        assertThatThrownBy(() -> service.issueInitialCode(userId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("userId must be positive");

        verifyNoInteractions(appUserRepository,challengeRepository,codeGenerator,rateLimiter,eventPublisher);
    }



    @Test
    void rejectsInitialIssuanceForMissingUser() {
        when(appUserRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issueInitialCode(USER_ID))
                .isInstanceOf(EmailVerificationUserNotFoundException.class);

        verifyNoInteractions(challengeRepository,codeGenerator,eventPublisher);
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void rejectsInitialIssuanceForDisabledOrVerifiedUser(boolean verified) {
        AppUser user = ineligibleUser(verified);
        when(appUserRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
        when(challengeRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issueInitialCode(USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Initial verification code cannot be issued for this user");

        verify(challengeRepository,never()).save(any(EmailVerificationChallenge.class));
        verifyNoInteractions(codeGenerator,eventPublisher);
    }



    @Test
    void rejectsInitialIssuanceWhenChallengeAlreadyExists() {
        AppUser user = user();
        EmailVerificationChallenge challenge = challenge(user,NOW.minusSeconds(60));
        when(appUserRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
        when(challengeRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(challenge));

        assertThatThrownBy(() -> service.issueInitialCode(USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Initial email verification challenge already exists");

        assertThat(challenge.getCodeHash()).isEqualTo(OLD_HASH);
        verify(challengeRepository,never()).save(any(EmailVerificationChallenge.class));
        verifyNoInteractions(codeGenerator,eventPublisher);
    }



    @Test
    void propagatesFailureWhenPublishingInitialDelivery() {
        AppUser user = user();
        IllegalStateException failure = new IllegalStateException("Delivery publication failed");
        when(appUserRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
        when(challengeRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.empty());
        when(codeGenerator.generate(USER_ID)).thenReturn(GENERATED_CODE);
        doThrow(failure).when(eventPublisher).publishEvent(any(EmailVerificationCodeDelivery.class));

        assertThatThrownBy(() -> service.issueInitialCode(USER_ID)).isSameAs(failure);

        verify(challengeRepository).save(any(EmailVerificationChallenge.class));
    }



    @Test
    void stopsRateLimitedResendBeforeRepositoryAccess() {
        when(rateLimiter.allowCodeIssue(EMAIL,CLIENT_KEY,NOW)).thenReturn(false);

        service.requestNewCode(" OWNER@Example.COM ",CLIENT_KEY);

        verify(rateLimiter).allowCodeIssue(EMAIL,CLIENT_KEY,NOW);
        verifyNoInteractions(appUserRepository,challengeRepository,codeGenerator,eventPublisher);
    }



    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "," \t\n "})
    void rateLimitsBlankAddressesWithoutLookingUpUsers(String email) {
        when(rateLimiter.allowCodeIssue("[invalid-address]",CLIENT_KEY,NOW)).thenReturn(true);

        service.requestNewCode(email,CLIENT_KEY);

        verify(rateLimiter).allowCodeIssue("[invalid-address]",CLIENT_KEY,NOW);
        verifyNoInteractions(appUserRepository,challengeRepository,codeGenerator,eventPublisher);
    }



    @Test
    void ignoresUnknownAddressesForRequestsAndVerification() {
        when(rateLimiter.allowCodeIssue(EMAIL,CLIENT_KEY,NOW)).thenReturn(true);
        when(rateLimiter.allowVerification(CLIENT_KEY,NOW)).thenReturn(true);
        when(appUserRepository.findByEmailForUpdate(EMAIL)).thenReturn(Optional.empty());

        service.requestNewCode(" OWNER@Example.COM ",CLIENT_KEY);

        assertThat(service.verifyCode(" OWNER@Example.COM ",RAW_CODE,CLIENT_KEY)).isEqualTo(EmailVerificationResult.INVALID);
        verifyNoInteractions(challengeRepository,codeGenerator,eventPublisher);
    }



    @Test
    void createsChallengeWhenEligibleUserRequestsCodeWithoutExistingChallenge() {
        AppUser user = user();
        stubRequest(user,null);
        when(codeGenerator.generate(USER_ID)).thenReturn(GENERATED_CODE);

        service.requestNewCode(" OWNER@Example.COM ",CLIENT_KEY);

        ArgumentCaptor<EmailVerificationChallenge> challengeCaptor = ArgumentCaptor.forClass(EmailVerificationChallenge.class);
        verify(challengeRepository).save(challengeCaptor.capture());

        assertThat(challengeCaptor.getValue().getUser()).isSameAs(user);
        assertThat(challengeCaptor.getValue().getCodeHash()).isEqualTo(GENERATED_CODE.codeHash());
        assertThat(challengeCaptor.getValue().getIssuedAt()).isEqualTo(NOW);
        assertDeliveryPublished();
    }



    @Test
    void preservesChallengeImmediatelyBeforeResendCooldownEnds() {
        AppUser user = user();
        Instant issuedAt = NOW.minus(RESEND_COOLDOWN).plusNanos(1);
        EmailVerificationChallenge challenge = challenge(user,issuedAt);
        challenge.recordFailedAttempt(NOW.minusSeconds(1));
        stubRequest(user,challenge);

        service.requestNewCode(EMAIL,CLIENT_KEY);

        assertThat(challenge.getCodeHash()).isEqualTo(OLD_HASH);
        assertThat(challenge.getIssuedAt()).isEqualTo(issuedAt);
        assertThat(challenge.getFailedAttempts()).isEqualTo(1);
        assertThat(challenge.getUpdatedAt()).isEqualTo(NOW.minusSeconds(1));
        verify(challengeRepository,never()).save(any(EmailVerificationChallenge.class));
        verifyNoInteractions(codeGenerator,eventPublisher);
    }



    @Test
    void rotatesChallengeAtExactResendBoundaryAndResetsFailedAttempts() {
        AppUser user = user();
        Instant originalIssuedAt = NOW.minus(RESEND_COOLDOWN);
        EmailVerificationChallenge challenge = challenge(user,originalIssuedAt);
        for (int attempt = 0; attempt < MAXIMUM_FAILED_ATTEMPTS; attempt++) {
            challenge.recordFailedAttempt(NOW.minusSeconds(1));
        }
        stubRequest(user,challenge);
        when(codeGenerator.generate(USER_ID)).thenReturn(GENERATED_CODE);

        service.requestNewCode(EMAIL,CLIENT_KEY);

        assertThat(challenge.getCodeHash()).isEqualTo(GENERATED_CODE.codeHash());
        assertThat(challenge.getIssuedAt()).isEqualTo(NOW);
        assertThat(challenge.getExpiresAt()).isEqualTo(NOW.plus(CODE_LIFETIME));
        assertThat(challenge.getResendAvailableAt()).isEqualTo(NOW.plus(RESEND_COOLDOWN));
        assertThat(challenge.getFailedAttempts()).isZero();
        assertThat(challenge.getCreatedAt()).isEqualTo(originalIssuedAt);
        assertThat(challenge.getUpdatedAt()).isEqualTo(NOW);
        verify(challengeRepository,never()).save(any(EmailVerificationChallenge.class));
        assertDeliveryPublished();
    }



    @Test
    void ignoresResendForDisabledUserWithoutDeletingChallenge() {
        AppUser user = ineligibleUser(false);
        EmailVerificationChallenge challenge = challenge(user,NOW.minusSeconds(60));
        stubRequest(user,challenge);

        service.requestNewCode(EMAIL,CLIENT_KEY);

        assertThat(challenge.getCodeHash()).isEqualTo(OLD_HASH);
        verify(challengeRepository,never()).delete(any(EmailVerificationChallenge.class));
        verifyNoInteractions(codeGenerator,eventPublisher);
    }



    @Test
    void removesStaleChallengeWhenVerifiedUserRequestsAnotherCode() {
        AppUser user = ineligibleUser(true);
        EmailVerificationChallenge challenge = challenge(user,NOW.minusSeconds(60));
        stubRequest(user,challenge);

        service.requestNewCode(EMAIL,CLIENT_KEY);

        verify(challengeRepository).delete(challenge);
        assertThat(user.getEmailVerifiedAt()).isEqualTo(NOW.minusSeconds(10));
        verifyNoInteractions(codeGenerator,eventPublisher);
    }



    @Test
    void rejectsRateLimitedVerificationBeforeRepositoryAccess() {
        when(rateLimiter.allowVerification(CLIENT_KEY,NOW)).thenReturn(false);

        assertThat(service.verifyCode(EMAIL,RAW_CODE,CLIENT_KEY)).isEqualTo(EmailVerificationResult.INVALID);

        verifyNoInteractions(appUserRepository,challengeRepository,codeGenerator,eventPublisher);
    }



    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "," \t\n "})
    void rejectsBlankVerificationAddressAfterCheckingClientLimit(String email) {
        when(rateLimiter.allowVerification(CLIENT_KEY,NOW)).thenReturn(true);

        assertThat(service.verifyCode(email,RAW_CODE,CLIENT_KEY)).isEqualTo(EmailVerificationResult.INVALID);

        verify(rateLimiter).allowVerification(CLIENT_KEY,NOW);
        verifyNoInteractions(appUserRepository,challengeRepository,codeGenerator,eventPublisher);
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void rejectsIneligibleUserAndDeletesChallengeOnlyWhenAlreadyVerified(boolean verified) {
        AppUser user = ineligibleUser(verified);
        EmailVerificationChallenge challenge = challenge(user,NOW.minusSeconds(60));
        stubVerification(user,challenge);

        assertThat(service.verifyCode(EMAIL,RAW_CODE,CLIENT_KEY)).isEqualTo(EmailVerificationResult.INVALID);

        if (verified) {
            verify(challengeRepository).delete(challenge);
        } else {
            verify(challengeRepository,never()).delete(any(EmailVerificationChallenge.class));
        }
        assertThat(challenge.getFailedAttempts()).isZero();
        verifyNoInteractions(codeGenerator,eventPublisher);
    }



    @Test
    void rejectsVerificationWithoutChallenge() {
        stubVerification(user(),null);

        assertThat(service.verifyCode(EMAIL,RAW_CODE,CLIENT_KEY)).isEqualTo(EmailVerificationResult.INVALID);

        verifyNoInteractions(codeGenerator,eventPublisher);
    }



    @ParameterizedTest
    @ValueSource(longs = {0,1})
    void rejectsCodeAtOrAfterExpiryWithoutRecordingAnotherAttempt(long secondsAfterExpiry) {
        AppUser user = user();
        EmailVerificationChallenge challenge = challenge(user,NOW.minus(CODE_LIFETIME).minusSeconds(secondsAfterExpiry));
        stubVerification(user,challenge);

        assertThat(service.verifyCode(EMAIL,RAW_CODE,CLIENT_KEY)).isEqualTo(EmailVerificationResult.INVALID);

        assertThat(challenge.getFailedAttempts()).isZero();
        assertThat(user.isEmailVerified()).isFalse();
        verify(challengeRepository,never()).delete(any(EmailVerificationChallenge.class));
        verifyNoInteractions(codeGenerator,eventPublisher);
    }



    @Test
    void rejectsCodeAfterAttemptLimitWithoutMatchingOrIncrementingAgain() {
        AppUser user = user();
        EmailVerificationChallenge challenge = challenge(user,NOW.minusSeconds(60));
        for (int attempt = 0; attempt < MAXIMUM_FAILED_ATTEMPTS; attempt++) {
            challenge.recordFailedAttempt(NOW.minusSeconds(1));
        }
        stubVerification(user,challenge);

        assertThat(service.verifyCode(EMAIL,RAW_CODE,CLIENT_KEY)).isEqualTo(EmailVerificationResult.INVALID);

        assertThat(challenge.getFailedAttempts()).isEqualTo(MAXIMUM_FAILED_ATTEMPTS);
        assertThat(challenge.getUpdatedAt()).isEqualTo(NOW.minusSeconds(1));
        assertThat(user.isEmailVerified()).isFalse();
        verifyNoInteractions(codeGenerator,eventPublisher);
    }



    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"1234567","abcdefgh","99999999"})
    void recordsFailedAttemptWhenSubmittedCodeDoesNotMatch(String rawCode) {
        AppUser user = user();
        EmailVerificationChallenge challenge = challenge(user,NOW.minusSeconds(60));
        stubVerification(user,challenge);
        when(codeGenerator.matches(USER_ID,rawCode,OLD_HASH)).thenReturn(false);

        assertThat(service.verifyCode(" OWNER@Example.COM ",rawCode,CLIENT_KEY)).isEqualTo(EmailVerificationResult.INVALID);

        assertThat(challenge.getFailedAttempts()).isEqualTo(1);
        assertThat(challenge.getUpdatedAt()).isEqualTo(NOW);
        assertThat(challenge.getCodeHash()).isEqualTo(OLD_HASH);
        assertThat(user.isEmailVerified()).isFalse();
        verify(codeGenerator).matches(USER_ID,rawCode,OLD_HASH);
        verify(challengeRepository,never()).delete(any(EmailVerificationChallenge.class));
        verifyNoInteractions(eventPublisher);
    }



    @Test
    void verifiesOnLastAllowedAttemptImmediatelyBeforeExpiryAndConsumesChallenge() {
        AppUser user = user();
        EmailVerificationChallenge challenge = new EmailVerificationChallenge(user,OLD_HASH,NOW.minusSeconds(120),NOW.plusNanos(1),NOW.minusSeconds(60));
        for (int attempt = 0; attempt < MAXIMUM_FAILED_ATTEMPTS - 1; attempt++) {
            challenge.recordFailedAttempt(NOW.minusSeconds(1));
        }
        stubVerification(user,challenge);
        when(codeGenerator.matches(USER_ID,RAW_CODE,OLD_HASH)).thenReturn(true);

        assertThat(service.verifyCode(" OWNER@Example.COM ",RAW_CODE,CLIENT_KEY)).isEqualTo(EmailVerificationResult.VERIFIED);

        assertThat(user.getEmailVerifiedAt()).isEqualTo(NOW);
        assertThat(user.getUpdatedAt()).isEqualTo(NOW);
        assertThat(challenge.getFailedAttempts()).isEqualTo(MAXIMUM_FAILED_ATTEMPTS - 1);
        verify(challengeRepository).delete(challenge);
        verifyNoInteractions(eventPublisher);
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void allowsDeliveryOnlyWhenCodeMatchesCurrentChallenge(boolean matches) {
        AppUser user = user();
        EmailVerificationChallenge challenge = challenge(user,NOW.minusSeconds(60));
        EmailVerificationCodeDelivery delivery = new EmailVerificationCodeDelivery(USER_ID,EMAIL,PreferredLanguage.TURKISH,RAW_CODE,challenge.getExpiresAt());
        when(appUserRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
        when(challengeRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(challenge));
        when(codeGenerator.matches(USER_ID,RAW_CODE,OLD_HASH)).thenReturn(matches);

        assertThat(service.canDeliverCode(delivery)).isEqualTo(matches);

        verifyNoInteractions(rateLimiter,eventPublisher);
    }



    private void stubRequest(AppUser user,EmailVerificationChallenge challenge) {
        when(rateLimiter.allowCodeIssue(EMAIL,CLIENT_KEY,NOW)).thenReturn(true);
        when(appUserRepository.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(user));
        when(challengeRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.ofNullable(challenge));
    }



    private void stubVerification(AppUser user,EmailVerificationChallenge challenge) {
        when(rateLimiter.allowVerification(CLIENT_KEY,NOW)).thenReturn(true);
        when(appUserRepository.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(user));
        when(challengeRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.ofNullable(challenge));
    }



    private void assertDeliveryPublished() {
        ArgumentCaptor<EmailVerificationCodeDelivery> deliveryCaptor = ArgumentCaptor.forClass(EmailVerificationCodeDelivery.class);
        verify(eventPublisher).publishEvent(deliveryCaptor.capture());
        EmailVerificationCodeDelivery delivery = deliveryCaptor.getValue();

        assertThat(delivery.userId()).isEqualTo(USER_ID);
        assertThat(delivery.recipientEmail()).isEqualTo(EMAIL);
        assertThat(delivery.preferredLanguage()).isEqualTo(PreferredLanguage.TURKISH);
        assertThat(delivery.rawCode()).isEqualTo(GENERATED_CODE.rawCode());
        assertThat(delivery.expiresAt()).isEqualTo(NOW.plus(CODE_LIFETIME));
    }



    private static EmailVerificationChallenge challenge(AppUser user,Instant issuedAt) {
        return new EmailVerificationChallenge(user,OLD_HASH,issuedAt,issuedAt.plus(CODE_LIFETIME),issuedAt.plus(RESEND_COOLDOWN));
    }



    private static AppUser ineligibleUser(boolean verified) {
        AppUser user = user();
        if (verified) {
            user.verifyEmail(NOW.minusSeconds(10));
        } else {
            user.disable(NOW.minusSeconds(10));
        }
        return user;
    }



    private static AppUser user() {
        AppUser user = new AppUser(EMAIL,"test-password-hash",PreferredLanguage.TURKISH,TemperatureUnit.CELSIUS,"UTC",CREATED_AT);
        ReflectionTestUtils.setField(user,"id",USER_ID);
        return user;
    }
}