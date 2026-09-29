package com.enginertugrul.iotsensormonitor.service.notification;

import com.enginertugrul.iotsensormonitor.config.EmailAlertMailConfig;
import com.enginertugrul.iotsensormonitor.config.EmailVerificationMailConfig;
import com.enginertugrul.iotsensormonitor.config.PasswordRecoveryMailConfig;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.entity.user.EmailVerificationChallenge;
import com.enginertugrul.iotsensormonitor.entity.user.PasswordResetChallenge;
import com.enginertugrul.iotsensormonitor.entity.user.PreferredLanguage;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.repository.AppUserRepository;
import com.enginertugrul.iotsensormonitor.repository.EmailVerificationChallengeRepository;
import com.enginertugrul.iotsensormonitor.repository.PasswordResetChallengeRepository;
import com.enginertugrul.iotsensormonitor.security.recovery.PasswordRecoveryHmac;
import com.enginertugrul.iotsensormonitor.security.verification.EmailVerificationHmac;
import com.enginertugrul.iotsensormonitor.service.alert.AlertTriggeredEvent;
import com.enginertugrul.iotsensormonitor.service.user.recovery.PasswordRecoveryCodeDelivery;
import com.enginertugrul.iotsensormonitor.service.user.recovery.PasswordRecoveryService;
import com.enginertugrul.iotsensormonitor.service.user.verification.EmailVerificationCodeDelivery;
import com.enginertugrul.iotsensormonitor.service.user.verification.EmailVerificationService;
import com.enginertugrul.iotsensormonitor.testsupport.PostgresTestConfiguration;
import com.enginertugrul.iotsensormonitor.testsupport.TestRuntimeConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.enginertugrul.iotsensormonitor.testsupport.TestRuntimeConfiguration.TEST_INSTANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;



@SpringBootTest(properties = {
        "spring.config.location=classpath:/application-test.properties",
        "app.scheduling.enabled=false",
        "app.mail.alerts.enabled=true",
        "app.mail.email-verification.enabled=true",
        "app.mail.password-recovery.enabled=true",
        "app.mail.retry.maximum-attempts=3",
        "app.mail.retry.initial-delay=PT0S",
        "app.mail.retry.multiplier=1.0",
        "app.mail.retry.maximum-delay=PT1S",
        "app.mail.retry.jitter=PT0S",
        "spring.mail.username=notifications@example.com"
})
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class,TestRuntimeConfiguration.class})
@Execution(ExecutionMode.SAME_THREAD)
class NotificationTransactionIT {


    private static final String ORIGINAL_CODE = "00123456";
    private static final String REPLACEMENT_CODE = "00876543";
    private static final Instant ROTATED_AT = TEST_INSTANT.plusSeconds(60);

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private EmailVerificationChallengeRepository verificationChallenges;

    @Autowired
    private PasswordResetChallengeRepository recoveryChallenges;

    @Autowired
    private EmailVerificationHmac verificationHmac;

    @Autowired
    private PasswordRecoveryHmac recoveryHmac;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean(name = EmailAlertMailConfig.EMAIL_ALERT_MAIL_EXECUTOR,enforceOverride = true)
    private TaskExecutor alertExecutor;

    @MockitoBean(name = EmailVerificationMailConfig.EMAIL_VERIFICATION_MAIL_EXECUTOR,enforceOverride = true)
    private TaskExecutor verificationExecutor;

    @MockitoBean(name = PasswordRecoveryMailConfig.PASSWORD_RECOVERY_MAIL_EXECUTOR,enforceOverride = true)
    private TaskExecutor recoveryExecutor;

    @MockitoBean(name = "testMailSender",enforceOverride = true)
    private JavaMailSender mailSender;

    @MockitoSpyBean
    private EmailVerificationService verificationService;

    @MockitoSpyBean
    private PasswordRecoveryService recoveryService;

    @MockitoSpyBean(name = "testClock")
    private Clock clock;

    private final Deque<Runnable> pendingTasks = new ArrayDeque<>();
    private final List<Long> fixtureUserIds = new ArrayList<>();

    private TransactionTemplate transactions;
    private boolean commitCompleted;



    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(30);

        doReturn(TEST_INSTANT).when(clock).instant();
        clearInvocations(verificationService,recoveryService);

        queueTasks(alertExecutor);
        queueTasks(verificationExecutor);
        queueTasks(recoveryExecutor);
    }



    @AfterEach
    void tearDown() {
        pendingTasks.clear();

        for (Long userId : fixtureUserIds) {
            jdbcTemplate.update("DELETE FROM app_users WHERE id=?",userId);
        }
    }



    @ParameterizedTest
    @EnumSource(NotificationType.class)
    void submitsAndDeliversOnlyAfterCommit(NotificationType type) {
        NotificationFixture fixture = commitNotification(type);

        assertThat(appUserRepository.findById(fixture.userId())).isPresent();
        assertThat(commitCompleted).isTrue();
        assertSubmissions(type,1);
        verifyNoInteractions(mailSender,verificationService,recoveryService);

        runOnlyTask();

        assertMessage(fixture,capturedMessages(1).getFirst());
        verifyEligibilityChecks(fixture,1);
    }



    @ParameterizedTest
    @EnumSource(NotificationType.class)
    void neverSubmitsWhenTransactionRollsBack(NotificationType type) {
        NotificationFixture fixture = transactions.execute(status -> {
            NotificationFixture created = createFixture(type);
            publishInCurrentTransaction(created.event());
            assertNoDispatch();
            status.setRollbackOnly();
            return created;
        });

        assertThat(fixture).isNotNull();
        assertThat(commitCompleted).isFalse();
        assertThat(appUserRepository.findById(fixture.userId())).isEmpty();
        assertNoDispatch();
    }



    @ParameterizedTest
    @EnumSource(NotificationType.class)
    void ignoresEventsPublishedWithoutATransaction(NotificationType type) {
        NotificationFixture fixture = transactions.execute(status -> createFixture(type));

        assertThat(fixture).isNotNull();
        assertThat(appUserRepository.findById(fixture.userId())).isPresent();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

        eventPublisher.publishEvent(fixture.event());

        assertNoDispatch();
    }



    @ParameterizedTest
    @EnumSource(value = NotificationType.class,names = {"VERIFICATION","RECOVERY"})
    void retriesEligibleCodesThroughTheRealSender(NotificationType type) {
        NotificationFixture fixture = commitNotification(type);
        doThrow(new MailSendException("Temporary transport failure"))
                .doNothing().when(mailSender).send(any(SimpleMailMessage.class));

        runOnlyTask();

        List<SimpleMailMessage> messages = capturedMessages(2);
        assertMessage(fixture,messages.getFirst());
        assertMessage(fixture,messages.getLast());
        verifyEligibilityChecks(fixture,2);
        assertSubmissions(type,1);
    }



    @ParameterizedTest
    @EnumSource(value = NotificationType.class,names = {"VERIFICATION","RECOVERY"})
    void suppressesRotatedCodeOnRetryAndDeliversItsReplacement(NotificationType type) {
        NotificationFixture original = commitNotification(type);
        NotificationFixture replacement = new NotificationFixture(
                type,original.userId(),original.email(),
                event(type,original.userId(),original.email(),REPLACEMENT_CODE,ROTATED_AT)
        );
        AtomicInteger transportAttempts = new AtomicInteger();

        doAnswer(invocation -> {
            if (transportAttempts.incrementAndGet() == 1) {
                rotateChallenge(original);
                throw new MailSendException("Temporary transport failure");
            }
            return null;
        }).when(mailSender).send(any(SimpleMailMessage.class));

        runOnlyTask();

        assertThat(transportAttempts.get()).isEqualTo(1);
        assertMessage(original,capturedMessages(1).getFirst());
        verifyEligibilityChecks(original,2);
        assertSubmissions(type,1);

        transactions.executeWithoutResult(status -> {
            publishInCurrentTransaction(replacement.event());
            assertThat(pendingTasks).isEmpty();
        });

        assertThat(commitCompleted).isTrue();
        assertSubmissions(type,2);

        runOnlyTask();

        assertThat(transportAttempts.get()).isEqualTo(2);
        List<SimpleMailMessage> messages = capturedMessages(2);
        assertMessage(original,messages.getFirst());
        assertMessage(replacement,messages.getLast());
        assertThat(messages.getLast().getText()).doesNotContain(ORIGINAL_CODE);
        verifyEligibilityChecks(original,2);
        verifyEligibilityChecks(replacement,1);
    }



    private NotificationFixture commitNotification(NotificationType type) {
        NotificationFixture fixture = transactions.execute(status -> {
            NotificationFixture created = createFixture(type);
            publishInCurrentTransaction(created.event());
            assertNoDispatch();
            return created;
        });

        assertThat(fixture).isNotNull();
        assertThat(commitCompleted).isTrue();
        assertThat(pendingTasks).hasSize(1);
        return fixture;
    }



    private NotificationFixture createFixture(NotificationType type) {
        String email = "notification-" + UUID.randomUUID() + "@example.com";
        AppUser user = new AppUser(
                email,"test-password-hash",PreferredLanguage.ENGLISH,TemperatureUnit.CELSIUS,"UTC",
                TEST_INSTANT.minusSeconds(3600)
        );

        if (type != NotificationType.VERIFICATION) {
            user.verifyEmail(TEST_INSTANT);
        }

        user = appUserRepository.saveAndFlush(user);
        fixtureUserIds.add(user.getId());

        Instant expiresAt = TEST_INSTANT.plusSeconds(600);
        Instant resendAvailableAt = TEST_INSTANT.plusSeconds(60);

        switch (type) {
            case ALERT -> {
            }
            case VERIFICATION -> verificationChallenges.saveAndFlush(new EmailVerificationChallenge(
                    user,codeHash(type,user.getId(),ORIGINAL_CODE),TEST_INSTANT,expiresAt,resendAvailableAt));
            case RECOVERY -> recoveryChallenges.saveAndFlush(new PasswordResetChallenge(
                    user,codeHash(type,user.getId(),ORIGINAL_CODE),TEST_INSTANT,expiresAt,resendAvailableAt));
        }

        return new NotificationFixture(type,user.getId(),email,event(type,user.getId(),email,ORIGINAL_CODE,TEST_INSTANT));
    }



    private void publishInCurrentTransaction(Object event) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        commitCompleted = false;

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                commitCompleted = true;
            }
        });

        eventPublisher.publishEvent(event);
    }



    private void queueTasks(TaskExecutor executor) {
        doAnswer(invocation -> {
            assertThat(commitCompleted).as("Mail task submission must follow the database commit").isTrue();
            pendingTasks.addLast(invocation.getArgument(0,Runnable.class));
            return null;
        }).when(executor).execute(any(Runnable.class));
    }



    private void runOnlyTask() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(pendingTasks).hasSize(1);

        Runnable task = pendingTasks.removeFirst();
        assertThatCode(task::run).doesNotThrowAnyException();

        assertThat(pendingTasks).isEmpty();
    }



    private void assertNoDispatch() {
        assertThat(pendingTasks).isEmpty();
        verifyNoInteractions(alertExecutor,verificationExecutor,recoveryExecutor);
        verifyNoInteractions(mailSender,verificationService,recoveryService);
    }



    private void assertSubmissions(NotificationType type,int count) {
        TaskExecutor expectedExecutor = switch (type) {
            case ALERT -> alertExecutor;
            case VERIFICATION -> verificationExecutor;
            case RECOVERY -> recoveryExecutor;
        };

        verify(expectedExecutor,times(count)).execute(any(Runnable.class));
        verifyNoMoreInteractions(alertExecutor,verificationExecutor,recoveryExecutor);
    }



    private void verifyEligibilityChecks(NotificationFixture fixture,int count) {
        switch (fixture.type()) {
            case ALERT -> verifyNoInteractions(verificationService,recoveryService);
            case VERIFICATION -> {
                verify(verificationService,times(count)).canDeliverCode(same((EmailVerificationCodeDelivery) fixture.event()));
                verifyNoInteractions(recoveryService);
            }
            case RECOVERY -> {
                verify(recoveryService,times(count)).canDeliverCode(same((PasswordRecoveryCodeDelivery) fixture.event()));
                verifyNoInteractions(verificationService);
            }
        }
    }



    private List<SimpleMailMessage> capturedMessages(int count) {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender,times(count)).send(captor.capture());
        verifyNoMoreInteractions(mailSender);
        return captor.getAllValues();
    }



    private void assertMessage(NotificationFixture fixture,SimpleMailMessage message) {
        assertThat(message.getFrom()).isEqualTo("notifications@example.com");
        assertThat(message.getTo()).containsExactly(fixture.email());

        switch (fixture.type()) {
            case ALERT -> {
                assertThat(message.getSubject()).isEqualTo("Motion alert: Hall sensor");
                assertThat(message.getText()).contains("Sensor: Hall sensor");
            }
            case VERIFICATION -> {
                EmailVerificationCodeDelivery delivery = (EmailVerificationCodeDelivery) fixture.event();
                assertThat(message.getSubject()).isEqualTo("Your IoT Sensor Monitor verification code");
                assertThat(message.getText()).contains("\n\n" + delivery.rawCode() + "\n\n");
            }
            case RECOVERY -> {
                PasswordRecoveryCodeDelivery delivery = (PasswordRecoveryCodeDelivery) fixture.event();
                assertThat(message.getSubject()).isEqualTo("Your IoT Sensor Monitor password reset code");
                assertThat(message.getText()).contains("\n\n" + delivery.rawCode() + "\n\n");
            }
        }
    }



    private void rotateChallenge(NotificationFixture fixture) {
        doReturn(ROTATED_AT).when(clock).instant();
        String replacementHash = codeHash(fixture.type(),fixture.userId(),REPLACEMENT_CODE);
        Instant expiresAt = ROTATED_AT.plusSeconds(600);
        Instant resendAvailableAt = ROTATED_AT.plusSeconds(60);

        transactions.executeWithoutResult(status -> {
            switch (fixture.type()) {
                case VERIFICATION -> verificationChallenges.findByUserIdForUpdate(fixture.userId()).orElseThrow()
                        .rotateCode(replacementHash,ROTATED_AT,expiresAt,resendAvailableAt);
                case RECOVERY -> recoveryChallenges.findByUserIdForUpdate(fixture.userId()).orElseThrow()
                        .rotateCode(replacementHash,ROTATED_AT,expiresAt,resendAvailableAt);
                case ALERT -> throw new IllegalArgumentException("Alert notifications do not have challenge codes");
            }
        });
    }



    private String codeHash(NotificationType type,long userId,String rawCode) {
        return switch (type) {
            case VERIFICATION -> verificationHmac.digest("email-verification-code",userId + ":" + rawCode);
            case RECOVERY -> recoveryHmac.digest("password-reset-code",userId + ":" + rawCode);
            case ALERT -> throw new IllegalArgumentException("Alert notifications do not have challenge codes");
        };
    }



    private static Object event(NotificationType type,long userId,String email,String rawCode,Instant issuedAt) {
        return switch (type) {
            case ALERT -> alertEvent(email,issuedAt);
            case VERIFICATION -> new EmailVerificationCodeDelivery(
                    userId,email,PreferredLanguage.ENGLISH,rawCode,issuedAt.plusSeconds(600));
            case RECOVERY -> new PasswordRecoveryCodeDelivery(
                    userId,email,PreferredLanguage.ENGLISH,rawCode,issuedAt.plusSeconds(600));
        };
    }



    private static AlertTriggeredEvent alertEvent(String email,Instant recordedAt) {
        AlertTriggeredEvent.RecipientSnapshot recipient =
                new AlertTriggeredEvent.RecipientSnapshot(email,PreferredLanguage.ENGLISH,TemperatureUnit.CELSIUS,ZoneOffset.UTC);
        AlertTriggeredEvent.SensorSnapshot sensor =
                new AlertTriggeredEvent.SensorSnapshot(100L,SensorType.MOTION,"Hall sensor","Entrance","Istanbul","Kadikoy",ZoneOffset.UTC);
        AlertTriggeredEvent.Context context = new AlertTriggeredEvent.Context(200L,recipient,sensor,recordedAt,15);
        return new AlertTriggeredEvent(context,new AlertTriggeredEvent.MotionDetectedTrigger());
    }



    private enum NotificationType {
        ALERT,
        VERIFICATION,
        RECOVERY
    }

    private record NotificationFixture(NotificationType type,long userId,String email,Object event) {}
}