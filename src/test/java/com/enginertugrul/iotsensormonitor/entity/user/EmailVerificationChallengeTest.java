package com.enginertugrul.iotsensormonitor.entity.user;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.stream.Stream;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;



class EmailVerificationChallengeTest {

    private static final String CODE_HASH = "a".repeat(64);
    private static final String REPLACEMENT_HASH = "b".repeat(64);
    private static final Instant ISSUED_AT = CREATED_AT.plusSeconds(60);
    private static final Instant EXPIRES_AT = ISSUED_AT.plusSeconds(600);
    private static final Instant RESEND_AVAILABLE_AT = ISSUED_AT.plusSeconds(60);

    private final AppUser owner = user();



    @Test
    void createsChallengeWithNormalizedHashAndInitialState() {
        EmailVerificationChallenge challenge = new EmailVerificationChallenge(owner," \t" + CODE_HASH + "\r\n",ISSUED_AT,EXPIRES_AT,RESEND_AVAILABLE_AT);

        assertThat(challenge.getId()).isNull();
        assertThat(challenge.getUser()).isSameAs(owner);
        assertThat(challenge.getCodeHash()).isEqualTo(CODE_HASH);
        assertThat(challenge.getIssuedAt()).isEqualTo(ISSUED_AT);
        assertThat(challenge.getExpiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(challenge.getResendAvailableAt()).isEqualTo(RESEND_AVAILABLE_AT);
        assertThat(challenge.getFailedAttempts()).isZero();
        assertThat(challenge.getCreatedAt()).isEqualTo(ISSUED_AT);
        assertThat(challenge.getUpdatedAt()).isEqualTo(ISSUED_AT);
        assertThat(challenge.hasReachedAttemptLimit(1)).isFalse();
    }



    @Test
    void requiresAUser() {
        assertThatNullPointerException()
                .isThrownBy(() -> new EmailVerificationChallenge(null,CODE_HASH,ISSUED_AT,EXPIRES_AT,RESEND_AVAILABLE_AT))
                .withMessage("user must not be null");
    }



    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ","\t\r\n","\u2003"})
    void rejectsMissingOrBlankHashesOnCreationAndRotation(String codeHash) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EmailVerificationChallenge(owner,codeHash,ISSUED_AT,EXPIRES_AT,RESEND_AVAILABLE_AT))
                .withMessage("codeHash must not be blank");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> challenge().rotateCode(codeHash,ISSUED_AT,EXPIRES_AT,RESEND_AVAILABLE_AT))
                .withMessage("codeHash must not be blank");
    }



    @ParameterizedTest
    @MethodSource("missingTimestamps")
    void requiresEveryTimestampOnCreationAndRotation(Instant issuedAt,Instant expiresAt,Instant resendAvailableAt,String message) {
        assertThatNullPointerException()
                .isThrownBy(() -> new EmailVerificationChallenge(owner,CODE_HASH,issuedAt,expiresAt,resendAvailableAt))
                .withMessage(message);

        assertThatNullPointerException()
                .isThrownBy(() -> challenge().rotateCode(REPLACEMENT_HASH,issuedAt,expiresAt,resendAvailableAt))
                .withMessage(message);
    }



    @ParameterizedTest
    @MethodSource("invalidCodeWindows")
    void rejectsInvalidWindowsOnCreationAndRotation(Instant expiresAt,Instant resendAvailableAt,String message) {
        assertThatIllegalStateException()
                .isThrownBy(() -> new EmailVerificationChallenge(owner,CODE_HASH,ISSUED_AT,expiresAt,resendAvailableAt))
                .withMessage(message);

        assertThatIllegalStateException()
                .isThrownBy(() -> challenge().rotateCode(REPLACEMENT_HASH,ISSUED_AT,expiresAt,resendAvailableAt))
                .withMessage(message);
    }



    @ParameterizedTest
    @MethodSource("validResendTimes")
    void acceptsResendAtIssuanceAndImmediatelyBeforeExpiry(Instant resendAvailableAt) {
        EmailVerificationChallenge created = new EmailVerificationChallenge(owner,CODE_HASH,ISSUED_AT,EXPIRES_AT,resendAvailableAt);
        EmailVerificationChallenge rotated = challenge();

        rotated.rotateCode(REPLACEMENT_HASH,ISSUED_AT,EXPIRES_AT,resendAvailableAt);

        assertThat(created.getResendAvailableAt()).isEqualTo(resendAvailableAt);
        assertThat(rotated.getResendAvailableAt()).isEqualTo(resendAvailableAt);
        assertThat(created.isResendAvailableAt(resendAvailableAt)).isTrue();
        assertThat(rotated.isResendAvailableAt(resendAvailableAt)).isTrue();
    }



    @Test
    void acceptsAOneNanosecondLifetimeWithImmediateResendAvailability() {
        Instant expiresAt = ISSUED_AT.plusNanos(1);
        EmailVerificationChallenge challenge = new EmailVerificationChallenge(owner,CODE_HASH,ISSUED_AT,expiresAt,ISSUED_AT);

        assertThat(challenge.isExpiredAt(ISSUED_AT)).isFalse();
        assertThat(challenge.isExpiredAt(expiresAt)).isTrue();
        assertThat(challenge.isResendAvailableAt(ISSUED_AT)).isTrue();
    }



    @ParameterizedTest
    @ValueSource(longs = {60,600,1200})
    void rotatesBeforeAtOrAfterOldExpiryAndResetsAttempts(long elapsedSeconds) {
        EmailVerificationChallenge challenge = challenge();
        challenge.recordFailedAttempt(ISSUED_AT.plusSeconds(10));
        challenge.recordFailedAttempt(ISSUED_AT.plusSeconds(20));
        Instant rotatedAt = ISSUED_AT.plusSeconds(elapsedSeconds);
        Instant rotatedExpiry = rotatedAt.plusSeconds(900);
        Instant rotatedResend = rotatedAt.plusSeconds(30);

        challenge.rotateCode(" \t" + REPLACEMENT_HASH + "\r\n",rotatedAt,rotatedExpiry,rotatedResend);

        assertThat(challenge.getUser()).isSameAs(owner);
        assertThat(challenge.getCodeHash()).isEqualTo(REPLACEMENT_HASH);
        assertThat(challenge.getIssuedAt()).isEqualTo(rotatedAt);
        assertThat(challenge.getExpiresAt()).isEqualTo(rotatedExpiry);
        assertThat(challenge.getResendAvailableAt()).isEqualTo(rotatedResend);
        assertThat(challenge.getFailedAttempts()).isZero();
        assertThat(challenge.getCreatedAt()).isEqualTo(ISSUED_AT);
        assertThat(challenge.getUpdatedAt()).isEqualTo(rotatedAt);
        assertThat(challenge.hasReachedAttemptLimit(2)).isFalse();
        assertThat(challenge.isExpiredAt(EXPIRES_AT)).isFalse();
        assertThat(challenge.isResendAvailableAt(RESEND_AVAILABLE_AT)).isFalse();

        challenge.recordFailedAttempt(rotatedAt.plusSeconds(1));

        assertThat(challenge.getFailedAttempts()).isEqualTo(1);
        assertThat(challenge.getUpdatedAt()).isEqualTo(rotatedAt.plusSeconds(1));
        assertThat(challenge.getCreatedAt()).isEqualTo(ISSUED_AT);
    }



    @Test
    void rejectsRotationBeforeTheOriginalChallengeCreation() {
        Instant rotatedAt = ISSUED_AT.minusNanos(1);

        assertThatIllegalStateException()
                .isThrownBy(() -> challenge().rotateCode(REPLACEMENT_HASH,rotatedAt,rotatedAt.plusSeconds(600),rotatedAt.plusSeconds(60)))
                .withMessage("issuedAt must not be before createdAt");
    }



    @Test
    void recordsFailedAttemptsAtOrAfterIssuanceWithoutChangingTheCodeWindow() {
        EmailVerificationChallenge challenge = challenge();

        challenge.recordFailedAttempt(ISSUED_AT);

        assertThat(challenge.getFailedAttempts()).isEqualTo(1);
        assertThat(challenge.getUpdatedAt()).isEqualTo(ISSUED_AT);

        challenge.recordFailedAttempt(ISSUED_AT);
        challenge.recordFailedAttempt(ISSUED_AT.plusSeconds(10));

        assertThat(challenge.getFailedAttempts()).isEqualTo(3);
        assertThat(challenge.getUpdatedAt()).isEqualTo(ISSUED_AT.plusSeconds(10));
        assertThat(challenge.getCreatedAt()).isEqualTo(ISSUED_AT);
        assertThat(challenge.getIssuedAt()).isEqualTo(ISSUED_AT);
        assertThat(challenge.getExpiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(challenge.getResendAvailableAt()).isEqualTo(RESEND_AVAILABLE_AT);
        assertThat(challenge.getCodeHash()).isEqualTo(CODE_HASH);
        assertThat(challenge.getUser()).isSameAs(owner);
    }



    @Test
    void rejectsMissingAttemptTimeWithoutChangingTheCounter() {
        EmailVerificationChallenge challenge = challenge();
        Instant attemptedAt = ISSUED_AT.plusSeconds(10);
        challenge.recordFailedAttempt(attemptedAt);

        assertThatNullPointerException()
                .isThrownBy(() -> challenge.recordFailedAttempt(null))
                .withMessage("attemptedAt must not be null");

        assertThat(challenge.getFailedAttempts()).isEqualTo(1);
        assertThat(challenge.getUpdatedAt()).isEqualTo(attemptedAt);
    }



    @ParameterizedTest
    @ValueSource(longs = {0,120})
    void rejectsAttemptsBeforeCurrentIssuanceWithoutChangingTheCounter(long elapsedSeconds) {
        EmailVerificationChallenge challenge = challenge();
        Instant currentIssuedAt = ISSUED_AT.plusSeconds(elapsedSeconds);
        challenge.rotateCode(REPLACEMENT_HASH,currentIssuedAt,currentIssuedAt.plusSeconds(600),currentIssuedAt.plusSeconds(60));
        Instant attemptedAt = currentIssuedAt.plusSeconds(5);
        challenge.recordFailedAttempt(attemptedAt);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> challenge.recordFailedAttempt(currentIssuedAt.minusNanos(1)))
                .withMessage("attemptedAt must not be before issuedAt");

        assertThat(challenge.getFailedAttempts()).isEqualTo(1);
        assertThat(challenge.getUpdatedAt()).isEqualTo(attemptedAt);
    }



    @ParameterizedTest
    @CsvSource({"-1,false","0,true","1,true"})
    void evaluatesExpiryAtTheExactNanosecondBoundary(long offsetNanos,boolean expected) {
        assertThat(challenge().isExpiredAt(EXPIRES_AT.plusNanos(offsetNanos))).isEqualTo(expected);
    }



    @ParameterizedTest
    @CsvSource({"-1,false","0,true","1,true"})
    void evaluatesResendAvailabilityAtTheExactNanosecondBoundary(long offsetNanos,boolean expected) {
        assertThat(challenge().isResendAvailableAt(RESEND_AVAILABLE_AT.plusNanos(offsetNanos))).isEqualTo(expected);
    }



    @Test
    void resendAvailabilityIsIndependentOfExpiryAndAttemptLimit() {
        EmailVerificationChallenge challenge = challenge();

        for (int attempt = 1; attempt <= 3; attempt++) {
            challenge.recordFailedAttempt(ISSUED_AT.plusSeconds(attempt));
        }

        assertThat(challenge.hasReachedAttemptLimit(3)).isTrue();
        assertThat(challenge.isResendAvailableAt(RESEND_AVAILABLE_AT)).isTrue();
        assertThat(challenge.isExpiredAt(RESEND_AVAILABLE_AT)).isFalse();

        assertThat(challenge.isExpiredAt(EXPIRES_AT)).isTrue();
        assertThat(challenge.isResendAvailableAt(EXPIRES_AT)).isTrue();
    }



    @Test
    void requiresTimestampsForExpiryAndResendChecks() {
        EmailVerificationChallenge challenge = challenge();

        assertThatNullPointerException()
                .isThrownBy(() -> challenge.isExpiredAt(null))
                .withMessage("checkedAt must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> challenge.isResendAvailableAt(null))
                .withMessage("checkedAt must not be null");
    }



    @ParameterizedTest
    @ValueSource(ints = {1,5,10})
    void reachesAttemptLimitAtEqualityAndRemainsReachedAboveIt(int maximumFailedAttempts) {
        EmailVerificationChallenge challenge = challenge();

        for (int attempt = 1; attempt < maximumFailedAttempts; attempt++) {
            challenge.recordFailedAttempt(ISSUED_AT.plusSeconds(attempt));
        }

        assertThat(challenge.hasReachedAttemptLimit(maximumFailedAttempts)).isFalse();

        challenge.recordFailedAttempt(ISSUED_AT.plusSeconds(maximumFailedAttempts));

        assertThat(challenge.hasReachedAttemptLimit(maximumFailedAttempts)).isTrue();

        challenge.recordFailedAttempt(ISSUED_AT.plusSeconds(maximumFailedAttempts + 1));

        assertThat(challenge.getFailedAttempts()).isEqualTo(maximumFailedAttempts + 1);
        assertThat(challenge.hasReachedAttemptLimit(maximumFailedAttempts)).isTrue();
        assertThat(challenge.hasReachedAttemptLimit(maximumFailedAttempts + 2)).isFalse();
    }



    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE,-1,0})
    void rejectsNonPositiveAttemptLimits(int maximumFailedAttempts) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> challenge().hasReachedAttemptLimit(maximumFailedAttempts))
                .withMessage("maximumFailedAttempts must be positive");
    }



    @Test
    void persistenceCallbacksPreserveValidRotatedState() {
        EmailVerificationChallenge challenge = challenge();
        Instant rotatedAt = ISSUED_AT.plusSeconds(120);
        challenge.rotateCode(REPLACEMENT_HASH,rotatedAt,rotatedAt.plusSeconds(600),rotatedAt.plusSeconds(60));
        challenge.recordFailedAttempt(rotatedAt.plusSeconds(10));

        challenge.prePersist();
        challenge.preUpdate();

        assertThat(challenge.getCreatedAt()).isEqualTo(ISSUED_AT);
        assertThat(challenge.getIssuedAt()).isEqualTo(rotatedAt);
        assertThat(challenge.getUpdatedAt()).isEqualTo(rotatedAt.plusSeconds(10));
        assertThat(challenge.getFailedAttempts()).isEqualTo(1);
        assertThat(challenge.getCodeHash()).isEqualTo(REPLACEMENT_HASH);
    }



    @Test
    void prePersistInitializesMissingAuditTimestampsFromIssuance() {
        EmailVerificationChallenge challenge = challenge();
        ReflectionTestUtils.setField(challenge,"createdAt",null);
        ReflectionTestUtils.setField(challenge,"updatedAt",null);

        challenge.prePersist();

        assertThat(challenge.getCreatedAt()).isEqualTo(ISSUED_AT);
        assertThat(challenge.getUpdatedAt()).isEqualTo(ISSUED_AT);
    }



    @ParameterizedTest
    @ValueSource(strings = {"createdAt","updatedAt"})
    void preUpdateRequiresAuditTimestamps(String field) {
        EmailVerificationChallenge challenge = challenge();
        ReflectionTestUtils.setField(challenge,field,null);

        assertThatNullPointerException()
                .isThrownBy(challenge::preUpdate)
                .withMessage(field + " must not be null");
    }



    @ParameterizedTest
    @MethodSource("invalidStoredStates")
    void persistenceCallbacksRejectInvalidStoredState(String field,Object value,String message) {
        EmailVerificationChallenge challenge = challenge();
        ReflectionTestUtils.setField(challenge,field,value);

        assertThatIllegalStateException().isThrownBy(challenge::prePersist).withMessage(message);
        assertThatIllegalStateException().isThrownBy(challenge::preUpdate).withMessage(message);
    }



    private EmailVerificationChallenge challenge() {
        return new EmailVerificationChallenge(owner,CODE_HASH,ISSUED_AT,EXPIRES_AT,RESEND_AVAILABLE_AT);
    }



    private static Stream<Arguments> missingTimestamps() {
        return Stream.of(
                Arguments.of(null,EXPIRES_AT,RESEND_AVAILABLE_AT,"issuedAt must not be null"),
                Arguments.of(ISSUED_AT,null,RESEND_AVAILABLE_AT,"expiresAt must not be null"),
                Arguments.of(ISSUED_AT,EXPIRES_AT,null,"resendAvailableAt must not be null")
        );
    }



    private static Stream<Arguments> invalidCodeWindows() {
        return Stream.of(
                Arguments.of(ISSUED_AT.minusNanos(1),ISSUED_AT,"expiresAt must be after issuedAt"),
                Arguments.of(ISSUED_AT,ISSUED_AT,"expiresAt must be after issuedAt"),
                Arguments.of(EXPIRES_AT,ISSUED_AT.minusNanos(1),"resendAvailableAt must be between issuedAt and expiresAt"),
                Arguments.of(EXPIRES_AT,EXPIRES_AT,"resendAvailableAt must be between issuedAt and expiresAt"),
                Arguments.of(EXPIRES_AT,EXPIRES_AT.plusNanos(1),"resendAvailableAt must be between issuedAt and expiresAt")
        );
    }



    private static Stream<Instant> validResendTimes() {
        return Stream.of(ISSUED_AT,EXPIRES_AT.minusNanos(1));
    }



    private static Stream<Arguments> invalidStoredStates() {
        return Stream.of(
                Arguments.of("failedAttempts",-1,"failedAttempts must not be negative"),
                Arguments.of("issuedAt",ISSUED_AT.minusNanos(1),"issuedAt must not be before createdAt"),
                Arguments.of("expiresAt",ISSUED_AT,"expiresAt must be after issuedAt"),
                Arguments.of("resendAvailableAt",ISSUED_AT.minusNanos(1),"resendAvailableAt must be between issuedAt and expiresAt"),
                Arguments.of("resendAvailableAt",EXPIRES_AT,"resendAvailableAt must be between issuedAt and expiresAt"),
                Arguments.of("updatedAt",ISSUED_AT.minusNanos(1),"updatedAt must not be before createdAt")
        );
    }
}