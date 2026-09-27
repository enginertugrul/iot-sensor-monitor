package com.enginertugrul.iotsensormonitor.service.user.password;

import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.entity.user.PasswordResetChallenge;
import com.enginertugrul.iotsensormonitor.entity.user.PreferredLanguage;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.repository.AppUserRepository;
import com.enginertugrul.iotsensormonitor.repository.PasswordResetChallengeRepository;
import com.enginertugrul.iotsensormonitor.security.AuthenticatedUser;
import com.enginertugrul.iotsensormonitor.security.recovery.PasswordRecoveryHmac;
import com.enginertugrul.iotsensormonitor.service.user.recovery.PasswordRecoveryResult;
import com.enginertugrul.iotsensormonitor.service.user.recovery.PasswordRecoveryService;
import com.enginertugrul.iotsensormonitor.testsupport.PostgresTestConfiguration;
import com.enginertugrul.iotsensormonitor.testsupport.TestRuntimeConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static com.enginertugrul.iotsensormonitor.testsupport.TestRuntimeConfiguration.TEST_INSTANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;



@SpringBootTest(properties = "spring.config.location=classpath:/application-test.properties")
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class,TestRuntimeConfiguration.class})
@RecordApplicationEvents
@Execution(ExecutionMode.SAME_THREAD)
class PasswordChangeServiceIT {

    private static final String CURRENT_PASSWORD = "current-password";
    private static final String NEW_PASSWORD = "replacement-password";
    private static final String RESET_CODE = "12345678";

    @Autowired
    private PasswordChangeService service;

    @Autowired
    private PasswordRecoveryService recoveryService;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private PasswordResetChallengeRepository challengeRepository;

    @Autowired
    private PasswordRecoveryHmac recoveryHmac;

    @Autowired
    private SessionRegistry sessionRegistry;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private ApplicationEvents applicationEvents;

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoSpyBean
    private PasswordEncoder passwordEncoder;

    private final List<String> fixtureEmails = new ArrayList<>();
    private final List<String> fixtureSessionIds = new ArrayList<>();
    private TransactionTemplate transactions;



    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(30);
    }



    @AfterEach
    void tearDown() {
        for (String sessionId : fixtureSessionIds) {
            sessionRegistry.removeSessionInformation(sessionId);
        }
        for (String email : fixtureEmails) {
            jdbcTemplate.update("DELETE FROM app_users WHERE email=?",email);
        }
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void commitsPasswordChangeAndExpiresOnlyOwnersSessionsAfterCommit(boolean hasResetChallenge) {
        AppUser user = createUser();
        AppUser otherUser = createUser();
        if (hasResetChallenge) {
            saveChallenge(user);
        }
        PasswordResetChallenge otherChallenge = saveChallenge(otherUser);
        SessionInformation first = registerSession(user);
        SessionInformation second = registerSession(user);
        SessionInformation other = registerSession(otherUser);
        SessionInformation otherPrincipalType = registerSession(user.getEmail());

        PasswordChangeResult result = transactions.execute(status -> {
            PasswordChangeResult changed = service.changePassword(user.getId(),CURRENT_PASSWORD,NEW_PASSWORD);
            assertThat(changed).isEqualTo(PasswordChangeResult.PASSWORD_CHANGED);
            entityManager.flush();

            String pendingHash = jdbcTemplate.queryForObject("SELECT password_hash FROM app_users WHERE id=?",String.class,user.getId());
            assertThat(passwordEncoder.matches(NEW_PASSWORD,pendingHash)).isTrue();
            assertThat(challengeCount(user.getId())).isZero();
            assertSessionsActive(first,second,other,otherPrincipalType);
            return changed;
        });

        assertThat(result).isEqualTo(PasswordChangeResult.PASSWORD_CHANGED);
        AppUser changed = readUser(user.getId());
        assertThat(changed.getPasswordHash()).isNotEqualTo(user.getPasswordHash()).isNotEqualTo(NEW_PASSWORD);
        assertThat(passwordEncoder.matches(NEW_PASSWORD,changed.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(CURRENT_PASSWORD,changed.getPasswordHash())).isFalse();
        assertThat(changed.getUpdatedAt()).isEqualTo(TEST_INSTANT);
        assertThat(changed.getCreatedAt()).isEqualTo(user.getCreatedAt());
        assertThat(changed.getEmail()).isEqualTo(user.getEmail());
        assertThat(changed.getEmailVerifiedAt()).isEqualTo(user.getEmailVerifiedAt());
        assertThat(changed.getPreferredLanguage()).isEqualTo(user.getPreferredLanguage());
        assertThat(changed.getPreferredTemperatureUnit()).isEqualTo(user.getPreferredTemperatureUnit());
        assertThat(changed.getPreferredTimezone()).isEqualTo(user.getPreferredTimezone());
        assertThat(changed.isEnabled()).isTrue();
        assertThat(challengeCount(user.getId())).isZero();
        assertThat(first.isExpired()).isTrue();
        assertThat(second.isExpired()).isTrue();
        assertSessionsActive(other,otherPrincipalType);
        assertThat(passwordChanges(user.getId())).containsExactly(new PasswordChangedEvent(user.getId()));

        assertThat(readUser(otherUser.getId()).getPasswordHash()).isEqualTo(otherUser.getPasswordHash());
        assertThat(readUser(otherUser.getId()).getUpdatedAt()).isEqualTo(otherUser.getUpdatedAt());
        assertChallengeUnchanged(otherChallenge);
        assertThat(passwordChanges(otherUser.getId())).isEmpty();
    }



    @ParameterizedTest
    @MethodSource("rejectedPasswordChanges")
    void rejectedChangesPreservePasswordChallengeAndSessions(String currentPassword,String newPassword,PasswordChangeResult expected) {
        AppUser user = createUser();
        PasswordResetChallenge challenge = saveChallenge(user);
        SessionInformation first = registerSession(user);
        SessionInformation second = registerSession(user);

        assertThat(service.changePassword(user.getId(),currentPassword,newPassword)).isEqualTo(expected);

        assertPasswordUnchanged(user);
        assertChallengeUnchanged(challenge);
        assertSessionsActive(first,second);
        assertThat(passwordChanges(user.getId())).isEmpty();
    }



    @Test
    void rollsBackPasswordAndChallengeDeletionWithoutExpiringSessions() {
        AppUser user = createUser();
        AppUser otherUser = createUser();
        PasswordResetChallenge challenge = saveChallenge(user);
        SessionInformation first = registerSession(user);
        SessionInformation second = registerSession(user);
        SessionInformation other = registerSession(otherUser);
        IllegalStateException failure = new IllegalStateException("Rollback password change");

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            assertThat(service.changePassword(user.getId(),CURRENT_PASSWORD,NEW_PASSWORD)).isEqualTo(PasswordChangeResult.PASSWORD_CHANGED);
            entityManager.flush();

            String pendingHash = jdbcTemplate.queryForObject("SELECT password_hash FROM app_users WHERE id=?",String.class,user.getId());
            assertThat(passwordEncoder.matches(NEW_PASSWORD,pendingHash)).isTrue();
            assertThat(challengeCount(user.getId())).isZero();
            assertSessionsActive(first,second,other);
            throw failure;
        })).isSameAs(failure);

        assertPasswordUnchanged(user);
        assertChallengeUnchanged(challenge);
        assertSessionsActive(first,second,other);
        assertThat(readUser(otherUser.getId()).getPasswordHash()).isEqualTo(otherUser.getPasswordHash());

        assertThat(passwordChanges(user.getId())).containsExactly(new PasswordChangedEvent(user.getId()));
    }



    @Test
    void rollsBackResetChallengeDeletionWhenPasswordEncodingFails() {
        AppUser user = createUser();
        PasswordResetChallenge challenge = saveChallenge(user);
        SessionInformation session = registerSession(user);
        IllegalStateException failure = new IllegalStateException("Password encoding failed");
        doThrow(failure).when(passwordEncoder).encode(NEW_PASSWORD);

        assertThatThrownBy(() -> service.changePassword(user.getId(),CURRENT_PASSWORD,NEW_PASSWORD)).isSameAs(failure);

        assertPasswordUnchanged(user);
        assertChallengeUnchanged(challenge);
        assertSessionsActive(session);
        assertThat(passwordChanges(user.getId())).isEmpty();
    }



    @Test
    void changesPasswordSuccessfullyWhenUserHasNoRegisteredSessions() {
        AppUser user = createUser();

        assertThat(sessionRegistry.getAllSessions(new AuthenticatedUser(user),true)).isEmpty();

        assertThat(service.changePassword(user.getId(),CURRENT_PASSWORD,NEW_PASSWORD)).isEqualTo(PasswordChangeResult.PASSWORD_CHANGED);

        AppUser changed = readUser(user.getId());
        assertThat(passwordEncoder.matches(NEW_PASSWORD,changed.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(CURRENT_PASSWORD,changed.getPasswordHash())).isFalse();
        assertThat(changed.getUpdatedAt()).isEqualTo(TEST_INSTANT);
        assertThat(passwordChanges(user.getId())).containsExactly(new PasswordChangedEvent(user.getId()));
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void recoveryExpiresOwnersSessionsOnlyWhenPasswordResetCommits(boolean commit) {
        AppUser user = createUser();
        AppUser otherUser = createUser();
        PasswordResetChallenge challenge = saveChallenge(user);
        SessionInformation first = registerSession(user);
        SessionInformation second = registerSession(user);
        SessionInformation other = registerSession(otherUser);
        String clientKey = "password-change-it-" + UUID.randomUUID();

        transactions.executeWithoutResult(status -> {
            assertThat(recoveryService.resetPassword(user.getEmail(),RESET_CODE,NEW_PASSWORD,clientKey)).isEqualTo(PasswordRecoveryResult.PASSWORD_RESET);
            entityManager.flush();

            assertThat(challengeCount(user.getId())).isZero();
            assertSessionsActive(first,second,other);
            if (!commit) {
                status.setRollbackOnly();
            }
        });

        assertThat(first.isExpired()).isEqualTo(commit);
        assertThat(second.isExpired()).isEqualTo(commit);
        assertSessionsActive(other);
        assertThat(readUser(otherUser.getId()).getPasswordHash()).isEqualTo(otherUser.getPasswordHash());
        assertThat(passwordChanges(user.getId())).containsExactly(new PasswordChangedEvent(user.getId()));
        assertThat(passwordChanges(otherUser.getId())).isEmpty();

        if (commit) {
            AppUser changed = readUser(user.getId());
            assertThat(passwordEncoder.matches(NEW_PASSWORD,changed.getPasswordHash())).isTrue();
            assertThat(passwordEncoder.matches(CURRENT_PASSWORD,changed.getPasswordHash())).isFalse();
            assertThat(changed.getUpdatedAt()).isEqualTo(TEST_INSTANT);
            assertThat(challengeCount(user.getId())).isZero();
        } else {
            assertPasswordUnchanged(user);
            assertChallengeUnchanged(challenge);
        }
    }



    @Test
    void eventPublishedOutsideTransactionDoesNotExpireSessions() {
        AppUser user = createUser();
        SessionInformation session = registerSession(user);

        eventPublisher.publishEvent(new PasswordChangedEvent(user.getId()));

        assertSessionsActive(session);
        assertPasswordUnchanged(user);
        assertThat(passwordChanges(user.getId())).containsExactly(new PasswordChangedEvent(user.getId()));
    }



    private AppUser createUser() {
        String email = "password-change-" + UUID.randomUUID() + "@example.com";
        fixtureEmails.add(email);
        AppUser user = new AppUser(email,passwordEncoder.encode(CURRENT_PASSWORD),PreferredLanguage.TURKISH,TemperatureUnit.FAHRENHEIT,"Europe/Istanbul",TEST_INSTANT.minusSeconds(3600));
        user.verifyEmail(TEST_INSTANT.minusSeconds(1800));
        return appUserRepository.saveAndFlush(user);
    }



    private PasswordResetChallenge saveChallenge(AppUser user) {
        Instant issuedAt = TEST_INSTANT.minusSeconds(60);
        String codeHash = recoveryHmac.digest("password-reset-code",user.getId() + ":" + RESET_CODE);
        PasswordResetChallenge challenge = new PasswordResetChallenge(user,codeHash,issuedAt,TEST_INSTANT.plusSeconds(600),TEST_INSTANT);
        challenge.recordFailedAttempt(TEST_INSTANT.minusSeconds(30));
        challenge.recordFailedAttempt(TEST_INSTANT.minusSeconds(20));
        return challengeRepository.saveAndFlush(challenge);
    }



    private SessionInformation registerSession(AppUser user) {
        AuthenticatedUser principal = new AuthenticatedUser(user);
        principal.eraseCredentials();
        return registerSession(principal);
    }



    private SessionInformation registerSession(Object principal) {
        String sessionId = "password-change-session-" + UUID.randomUUID();
        fixtureSessionIds.add(sessionId);
        sessionRegistry.registerNewSession(sessionId,principal);
        SessionInformation information = sessionRegistry.getSessionInformation(sessionId);
        assertThat(information).isNotNull();
        return information;
    }



    private AppUser readUser(Long userId) {
        return appUserRepository.findById(userId).orElseThrow();
    }



    private long challengeCount(Long userId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM password_reset_challenges WHERE user_id=?",Long.class,userId);
    }



    private List<PasswordChangedEvent> passwordChanges(Long userId) {
        return applicationEvents.stream(PasswordChangedEvent.class)
                .filter(event -> event.userId().equals(userId))
                .toList();
    }



    private void assertPasswordUnchanged(AppUser original) {
        AppUser unchanged = readUser(original.getId());
        assertThat(unchanged.getPasswordHash()).isEqualTo(original.getPasswordHash());
        assertThat(unchanged.getUpdatedAt()).isEqualTo(original.getUpdatedAt());
        assertThat(passwordEncoder.matches(CURRENT_PASSWORD,unchanged.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(NEW_PASSWORD,unchanged.getPasswordHash())).isFalse();
    }



    private void assertChallengeUnchanged(PasswordResetChallenge original) {
        PasswordResetChallenge unchanged = challengeRepository.findById(original.getId()).orElseThrow();
        assertThat(unchanged.getCodeHash()).isEqualTo(original.getCodeHash());
        assertThat(unchanged.getIssuedAt()).isEqualTo(original.getIssuedAt());
        assertThat(unchanged.getExpiresAt()).isEqualTo(original.getExpiresAt());
        assertThat(unchanged.getResendAvailableAt()).isEqualTo(original.getResendAvailableAt());
        assertThat(unchanged.getFailedAttempts()).isEqualTo(original.getFailedAttempts());
        assertThat(unchanged.getCreatedAt()).isEqualTo(original.getCreatedAt());
        assertThat(unchanged.getUpdatedAt()).isEqualTo(original.getUpdatedAt());
    }



    private static void assertSessionsActive(SessionInformation... sessions) {
        for (SessionInformation session : sessions) {
            assertThat(session.isExpired()).as("Session %s should remain active",session.getSessionId()).isFalse();
        }
    }



    private static Stream<Arguments> rejectedPasswordChanges() {
        return Stream.of(
                Arguments.of("incorrect-password",NEW_PASSWORD,PasswordChangeResult.CURRENT_PASSWORD_INVALID),
                Arguments.of("short",NEW_PASSWORD,PasswordChangeResult.CURRENT_PASSWORD_INVALID),
                Arguments.of(CURRENT_PASSWORD,"short",PasswordChangeResult.NEW_PASSWORD_INVALID),
                Arguments.of(CURRENT_PASSWORD,CURRENT_PASSWORD,PasswordChangeResult.NEW_PASSWORD_UNCHANGED));
    }
}