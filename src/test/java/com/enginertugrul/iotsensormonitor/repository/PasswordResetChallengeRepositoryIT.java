package com.enginertugrul.iotsensormonitor.repository;

import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.entity.user.PasswordResetChallenge;
import com.enginertugrul.iotsensormonitor.testsupport.PostgresTestConfiguration;
import com.enginertugrul.iotsensormonitor.testsupport.TestRuntimeConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.stream.Stream;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = "spring.config.location=classpath:/application-test.properties")
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class,TestRuntimeConfiguration.class})
@Transactional
class PasswordResetChallengeRepositoryIT {

    private static final String CODE_HASH = "stored-password-reset-code-hash";
    private static final Instant ISSUED_AT = CREATED_AT.plusSeconds(60);
    private static final Instant EXPIRES_AT = ISSUED_AT.plusSeconds(600);
    private static final Instant RESEND_AVAILABLE_AT = ISSUED_AT.plusSeconds(60);

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private PasswordResetChallengeRepository challengeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;



    @Test
    void persistsAndFindsChallengesByUserId() {
        AppUser firstUser = persistUser("first@example.com");
        AppUser secondUser = persistUser("second@example.com");
        AppUser userWithoutChallenge = persistUser("without-challenge@example.com");

        PasswordResetChallenge second = persistChallenge(secondUser,EXPIRES_AT);
        PasswordResetChallenge first = persistChallenge(firstUser,EXPIRES_AT);
        flushAndClear();

        PasswordResetChallenge reloaded = challengeRepository.findByUserIdForUpdate(firstUser.getId()).orElseThrow();

        assertThat(reloaded.getId()).isPositive().isEqualTo(first.getId());
        assertThat(reloaded.getUser().getId()).isEqualTo(firstUser.getId());
        assertThat(reloaded.getCodeHash()).isEqualTo(CODE_HASH);
        assertThat(reloaded.getIssuedAt()).isEqualTo(ISSUED_AT);
        assertThat(reloaded.getExpiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(reloaded.getResendAvailableAt()).isEqualTo(RESEND_AVAILABLE_AT);
        assertThat(reloaded.getFailedAttempts()).isZero();
        assertThat(reloaded.getCreatedAt()).isEqualTo(ISSUED_AT);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(ISSUED_AT);

        assertThat(challengeRepository.findByUserIdForUpdate(secondUser.getId()).map(PasswordResetChallenge::getId))
                .contains(second.getId());
        assertThat(challengeRepository.findByUserIdForUpdate(userWithoutChallenge.getId())).isEmpty();
        assertThat(challengeRepository.findByUserIdForUpdate(-1L)).isEmpty();
    }



    @Test
    void persistsFailedAttemptsAcrossReloads() {
        AppUser user = persistUser("owner@example.com");
        PasswordResetChallenge original = persistChallenge(user,EXPIRES_AT);
        flushAndClear();

        PasswordResetChallenge firstAttempt = challengeRepository.findByUserIdForUpdate(user.getId()).orElseThrow();
        firstAttempt.recordFailedAttempt(ISSUED_AT.plusSeconds(10));
        flushAndClear();

        PasswordResetChallenge secondAttempt = challengeRepository.findByUserIdForUpdate(user.getId()).orElseThrow();

        assertThat(secondAttempt.getFailedAttempts()).isEqualTo(1);
        assertThat(secondAttempt.getUpdatedAt()).isEqualTo(ISSUED_AT.plusSeconds(10));

        secondAttempt.recordFailedAttempt(ISSUED_AT.plusSeconds(20));
        flushAndClear();

        PasswordResetChallenge reloaded = challengeRepository.findByUserIdForUpdate(user.getId()).orElseThrow();

        assertThat(reloaded.getId()).isEqualTo(original.getId());
        assertThat(reloaded.getFailedAttempts()).isEqualTo(2);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(ISSUED_AT.plusSeconds(20));
        assertThat(reloaded.getCreatedAt()).isEqualTo(ISSUED_AT);
        assertThat(reloaded.getIssuedAt()).isEqualTo(ISSUED_AT);
        assertThat(reloaded.getCodeHash()).isEqualTo(CODE_HASH);
    }



    @Test
    void flushesPendingRotationBeforeExpiryCleanup() {
        AppUser user = persistUser("owner@example.com");
        PasswordResetChallenge original = persistChallenge(user,EXPIRES_AT);
        original.recordFailedAttempt(ISSUED_AT.plusSeconds(10));
        flushAndClear();

        PasswordResetChallenge pending = challengeRepository.findByUserIdForUpdate(user.getId()).orElseThrow();
        assertThat(pending.getFailedAttempts()).isEqualTo(1);

        Instant rotatedAt = ISSUED_AT.plusSeconds(120);
        Instant rotatedExpiry = EXPIRES_AT.plusSeconds(120);
        Instant rotatedResend = RESEND_AVAILABLE_AT.plusSeconds(120);
        pending.rotateCode("rotated-password-reset-code-hash",rotatedAt,rotatedExpiry,rotatedResend);

        challengeRepository.deleteExpiredAtOrBefore(EXPIRES_AT);

        assertThat(entityManager.contains(pending)).isFalse();

        PasswordResetChallenge reloaded = challengeRepository.findByUserIdForUpdate(user.getId()).orElseThrow();

        assertThat(reloaded.getId()).isEqualTo(original.getId());
        assertThat(reloaded.getCodeHash()).isEqualTo("rotated-password-reset-code-hash");
        assertThat(reloaded.getIssuedAt()).isEqualTo(rotatedAt);
        assertThat(reloaded.getExpiresAt()).isEqualTo(rotatedExpiry);
        assertThat(reloaded.getResendAvailableAt()).isEqualTo(rotatedResend);
        assertThat(reloaded.getFailedAttempts()).isZero();
        assertThat(reloaded.getCreatedAt()).isEqualTo(ISSUED_AT);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(rotatedAt);
    }



    @Test
    void deletesChallengesExpiringAtOrBeforeTheCutoffAndPreservesUsers() {
        AppUser beforeUser = persistUser("before@example.com");
        AppUser atUser = persistUser("at@example.com");
        AppUser afterUser = persistUser("after@example.com");

        PasswordResetChallenge before = persistChallenge(beforeUser,EXPIRES_AT.minusNanos(1000));
        PasswordResetChallenge at = persistChallenge(atUser,EXPIRES_AT);
        PasswordResetChallenge after = persistChallenge(afterUser,EXPIRES_AT.plusNanos(1000));
        flushAndClear();

        challengeRepository.deleteExpiredAtOrBefore(EXPIRES_AT);

        assertThat(challengeRepository.findById(before.getId())).isEmpty();
        assertThat(challengeRepository.findById(at.getId())).isEmpty();
        assertThat(challengeRepository.findById(after.getId()).map(PasswordResetChallenge::getExpiresAt))
                .contains(EXPIRES_AT.plusNanos(1000));

        assertThat(appUserRepository.existsById(beforeUser.getId())).isTrue();
        assertThat(appUserRepository.existsById(atUser.getId())).isTrue();
        assertThat(appUserRepository.existsById(afterUser.getId())).isTrue();

        challengeRepository.deleteExpiredAtOrBefore(EXPIRES_AT);

        assertThat(challengeRepository.findByUserIdForUpdate(afterUser.getId()).map(PasswordResetChallenge::getId))
                .contains(after.getId());
    }



    @Test
    void rejectsASecondChallengeForTheSameUserThroughDirectSql() {
        AppUser user = persistUser("owner@example.com");
        PasswordResetChallenge challenge = persistChallenge(user,EXPIRES_AT);
        flushAndClear();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO password_reset_challenges (
                    user_id,code_hash,issued_at,expires_at,
                    resend_available_at,failed_attempts,created_at,updated_at
                )
                SELECT user_id,code_hash,issued_at,expires_at,
                       resend_available_at,failed_attempts,created_at,updated_at
                FROM password_reset_challenges
                WHERE id = ?
                """,challenge.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_password_reset_challenges_user_id");
    }



    @ParameterizedTest
    @MethodSource("validResendTimes")
    void acceptsResendTimesAtTheValidDatabaseBoundaries(Instant resendAvailableAt) {
        AppUser user = persistUser("owner@example.com");
        PasswordResetChallenge challenge = persistChallenge(user,EXPIRES_AT);
        flushAndClear();

        int updatedRows = jdbcTemplate.update("""
                UPDATE password_reset_challenges
                SET resend_available_at = ?
                WHERE id = ?
                """,Timestamp.from(resendAvailableAt),challenge.getId());

        assertThat(updatedRows).isEqualTo(1);
        assertThat(challengeRepository.findById(challenge.getId()).orElseThrow().getResendAvailableAt())
                .isEqualTo(resendAvailableAt);
    }



    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidChallengeValues")
    void rejectsInvalidChallengeValuesThroughDirectSql(String description,String column,Object value) {
        AppUser user = persistUser("owner@example.com");
        PasswordResetChallenge challenge = persistChallenge(user,EXPIRES_AT);
        flushAndClear();

        assertSqlState("23514",() -> jdbcTemplate.update(
                "UPDATE password_reset_challenges SET " + column + " = ? WHERE id = ?",value,challenge.getId()));
    }



    @ParameterizedTest
    @ValueSource(strings = {
            "user_id","code_hash","issued_at","expires_at",
            "resend_available_at","failed_attempts","created_at","updated_at"
    })
    void rejectsNullRequiredColumnsThroughDirectSql(String column) {
        AppUser user = persistUser("owner@example.com");
        PasswordResetChallenge challenge = persistChallenge(user,EXPIRES_AT);
        flushAndClear();

        assertSqlState("23502",() -> jdbcTemplate.update(
                "UPDATE password_reset_challenges SET " + column + " = NULL WHERE id = ?",challenge.getId()));
    }



    @Test
    void rejectsAChallengeReferencingAMissingUserThroughDirectSql() {
        AppUser user = persistUser("owner@example.com");
        PasswordResetChallenge challenge = persistChallenge(user,EXPIRES_AT);
        flushAndClear();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE password_reset_challenges
                SET user_id = ?
                WHERE id = ?
                """,-1L,challenge.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_password_reset_challenges_user");
    }



    @Test
    void cascadesUserDeletionToOnlyThatUsersChallenge() {
        AppUser deletedUser = persistUser("deleted@example.com");
        AppUser retainedUser = persistUser("retained@example.com");
        PasswordResetChallenge deleted = persistChallenge(deletedUser,EXPIRES_AT);
        PasswordResetChallenge retained = persistChallenge(retainedUser,EXPIRES_AT);
        flushAndClear();

        int deletedRows = jdbcTemplate.update("DELETE FROM app_users WHERE id = ?",deletedUser.getId());

        assertThat(deletedRows).isEqualTo(1);
        assertThat(appUserRepository.findById(deletedUser.getId())).isEmpty();
        assertThat(challengeRepository.findById(deleted.getId())).isEmpty();
        assertThat(challengeRepository.findByUserIdForUpdate(deletedUser.getId())).isEmpty();
        assertThat(appUserRepository.findById(retainedUser.getId())).isPresent();
        assertThat(challengeRepository.findByUserIdForUpdate(retainedUser.getId()).map(PasswordResetChallenge::getId))
                .contains(retained.getId());
    }



    private static Stream<Instant> validResendTimes() {
        return Stream.of(ISSUED_AT,EXPIRES_AT.minusNanos(1000));
    }



    private static Stream<Arguments> invalidChallengeValues() {
        return Stream.of(
                Arguments.of("empty hash","code_hash",""),
                Arguments.of("blank hash","code_hash","   "),
                Arguments.of("negative attempts","failed_attempts",-1),
                Arguments.of("issuance before creation","issued_at",Timestamp.from(ISSUED_AT.minusNanos(1000))),
                Arguments.of("expiry equal to issuance","expires_at",Timestamp.from(ISSUED_AT)),
                Arguments.of("expiry before issuance","expires_at",Timestamp.from(ISSUED_AT.minusNanos(1000))),
                Arguments.of("resend before issuance","resend_available_at",Timestamp.from(ISSUED_AT.minusNanos(1000))),
                Arguments.of("resend equal to expiry","resend_available_at",Timestamp.from(EXPIRES_AT)),
                Arguments.of("resend after expiry","resend_available_at",Timestamp.from(EXPIRES_AT.plusNanos(1000))),
                Arguments.of("update before creation","updated_at",Timestamp.from(ISSUED_AT.minusNanos(1000))));
    }



    private AppUser persistUser(String email) {
        AppUser user = new AppUser(email,"test-password-hash",CREATED_AT);
        user.verifyEmail(CREATED_AT);
        return appUserRepository.saveAndFlush(user);
    }



    private PasswordResetChallenge persistChallenge(AppUser user,Instant expiresAt) {
        return challengeRepository.saveAndFlush(new PasswordResetChallenge(user,CODE_HASH,ISSUED_AT,expiresAt,RESEND_AVAILABLE_AT));
    }



    private void assertSqlState(String expectedState,Runnable action) {
        DataIntegrityViolationException exception = assertThrows(DataIntegrityViolationException.class,action::run);

        assertThat(exception.getMostSpecificCause()).isInstanceOf(SQLException.class);
        SQLException sqlException = (SQLException) exception.getMostSpecificCause();
        assertThat(sqlException.getSQLState()).isEqualTo(expectedState);
    }



    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}