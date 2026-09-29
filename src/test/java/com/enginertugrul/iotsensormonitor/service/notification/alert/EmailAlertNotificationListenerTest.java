package com.enginertugrul.iotsensormonitor.service.notification.alert;

import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.entity.user.PreferredLanguage;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.service.alert.AlertTriggeredEvent;
import com.enginertugrul.iotsensormonitor.service.notification.retry.EmailDeliveryRetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.mail.MailSendException;

import java.time.ZoneOffset;
import java.util.stream.Stream;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;



@ExtendWith(MockitoExtension.class)
class EmailAlertNotificationListenerTest {

    private static final long RULE_ID = 200L;
    private static final long SENSOR_ID = 100L;

    @Mock
    private AlertNotificationDispatcher notificationDispatcher;

    @Mock
    private EmailDeliveryRetryService retryService;

    @Mock
    private TaskExecutor mailExecutor;

    @Captor
    private ArgumentCaptor<Runnable> taskCaptor;

    @Captor
    private ArgumentCaptor<Runnable> operationCaptor;

    private final AlertTriggeredEvent event = event();

    private EmailAlertNotificationListener listener;

    @BeforeEach
    void setUp() {
        listener = new EmailAlertNotificationListener(notificationDispatcher,retryService,mailExecutor);
    }



    @Test
    void defersDeliveryAndDelegatesARepeatableOperationToRetryService() {
        listener.onAlertTriggered(event);

        Runnable task = submittedTask();
        verifyNoInteractions(retryService,notificationDispatcher);

        assertThatCode(task::run).doesNotThrowAnyException();

        verify(retryService).send(eq("ALERT"),eq(RULE_ID),operationCaptor.capture());
        verifyNoInteractions(notificationDispatcher);

        Runnable operation = operationCaptor.getValue();
        assertThatCode(operation::run).doesNotThrowAnyException();
        assertThatCode(operation::run).doesNotThrowAnyException();

        verify(notificationDispatcher,times(2)).send(same(event));
        verifyNoMoreInteractions(mailExecutor,retryService,notificationDispatcher);
    }



    @Test
    void containsExecutorRejectionWithoutAttemptingDelivery() {
        doThrow(new TaskRejectedException("Mail executor is full")).when(mailExecutor).execute(any(Runnable.class));

        assertThatCode(() -> listener.onAlertTriggered(event)).doesNotThrowAnyException();

        verify(mailExecutor).execute(any(Runnable.class));
        verifyNoInteractions(retryService,notificationDispatcher);
        verifyNoMoreInteractions(mailExecutor);
    }



    @ParameterizedTest
    @MethodSource("deliveryFailures")
    void containsFailureReportedByRetryService(RuntimeException failure) {
        doThrow(failure).when(retryService).send(eq("ALERT"),eq(RULE_ID),any(Runnable.class));

        listener.onAlertTriggered(event);

        Runnable task = submittedTask();
        verifyNoInteractions(retryService,notificationDispatcher);

        assertThatCode(task::run).doesNotThrowAnyException();

        verify(retryService).send(eq("ALERT"),eq(RULE_ID),any(Runnable.class));
        verifyNoInteractions(notificationDispatcher);
        verifyNoMoreInteractions(mailExecutor,retryService);
    }



    @ParameterizedTest
    @MethodSource("deliveryFailures")
    void exposesDispatcherFailureToRetryServiceAndContainsTheFinalFailure(RuntimeException failure) {
        doThrow(failure).when(notificationDispatcher).send(same(event));
        doAnswer(invocation -> {
            Runnable operation = invocation.getArgument(2,Runnable.class);
            assertThatThrownBy(operation::run).isSameAs(failure);
            throw failure;
        }).when(retryService).send(eq("ALERT"),eq(RULE_ID),any(Runnable.class));

        listener.onAlertTriggered(event);

        Runnable task = submittedTask();
        verifyNoInteractions(retryService,notificationDispatcher);

        assertThatCode(task::run).doesNotThrowAnyException();

        verify(retryService).send(eq("ALERT"),eq(RULE_ID),any(Runnable.class));
        verify(notificationDispatcher).send(same(event));
        verifyNoMoreInteractions(mailExecutor,retryService,notificationDispatcher);
    }



    private Runnable submittedTask() {
        verify(mailExecutor).execute(taskCaptor.capture());
        verifyNoMoreInteractions(mailExecutor);
        return taskCaptor.getValue();
    }



    private static Stream<RuntimeException> deliveryFailures() {
        return Stream.of(
                new MailSendException("Mail delivery failed"),
                new IllegalStateException("Unexpected delivery failure")
        );
    }



    private static AlertTriggeredEvent event() {
        AlertTriggeredEvent.RecipientSnapshot recipient =
                new AlertTriggeredEvent.RecipientSnapshot("owner@example.com",PreferredLanguage.ENGLISH,TemperatureUnit.CELSIUS,ZoneOffset.UTC);
        AlertTriggeredEvent.SensorSnapshot sensor =
                new AlertTriggeredEvent.SensorSnapshot(SENSOR_ID,SensorType.MOTION,"Hall sensor","Entrance","Istanbul","Kadikoy",ZoneOffset.UTC);
        AlertTriggeredEvent.Context context = new AlertTriggeredEvent.Context(RULE_ID,recipient,sensor,CREATED_AT,15);
        return new AlertTriggeredEvent(context,new AlertTriggeredEvent.MotionDetectedTrigger());
    }
}