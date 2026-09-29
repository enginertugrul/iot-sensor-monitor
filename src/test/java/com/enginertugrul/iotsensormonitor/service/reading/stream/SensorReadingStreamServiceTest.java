package com.enginertugrul.iotsensormonitor.service.reading.stream;

import com.enginertugrul.iotsensormonitor.dto.reading.RecentSensorReadingsDTO;
import com.enginertugrul.iotsensormonitor.dto.reading.SensorReadingSnapshotDTO;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.exception.SensorNotFoundException;
import com.enginertugrul.iotsensormonitor.exception.SensorReadingStreamSessionExpiredException;
import com.enginertugrul.iotsensormonitor.exception.SensorReadingStreamUnavailableException;
import com.enginertugrul.iotsensormonitor.security.AuthenticatedUser;
import com.enginertugrul.iotsensormonitor.service.reading.SensorReadingService;
import com.enginertugrul.iotsensormonitor.testsupport.ControlledStreamExecutor;
import com.enginertugrul.iotsensormonitor.testsupport.RecordingSseEmitter;
import com.enginertugrul.iotsensormonitor.testsupport.RecordingSseEmitter.RecordedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;



class SensorReadingStreamServiceTest {

    private static final Long SENSOR_ID = 100L;
    private static final Long OWNER_ID = 42L;
    private static final String SESSION_ID = "owner-session";

    private static final Duration REFRESH_INTERVAL = Duration.ofSeconds(2);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(6);
    private static final Duration WORK_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CONNECTION_LIFETIME = Duration.ofSeconds(30);

    private SensorReadingService readingService;
    private SessionRegistryImpl sessionRegistry;
    private ControlledStreamExecutor executor;
    private AtomicLong nanoTime;
    private List<RecordingSseEmitter> emitters;
    private RecentSensorReadingsDTO initialSnapshot;
    private SensorReadingStreamService service;



    @BeforeEach
    void setUp() {
        readingService = mock(SensorReadingService.class);
        sessionRegistry = new SessionRegistryImpl();
        executor = new ControlledStreamExecutor();
        nanoTime = new AtomicLong();
        emitters = new ArrayList<>();
        initialSnapshot = snapshot(20);

        registerSession(SESSION_ID,OWNER_ID);
        doReturn(initialSnapshot).when(readingService)
                .getRecentReadingsSnapshot(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS);

        service = newService(1);
    }



    @AfterEach
    void tearDown() {
        try {
            service.shutdown();
            executor.runAll();
        } finally {
            executor.shutdown();
        }
    }



    @Test
    void rejectsMissingSensorAndOwnerBeforeLoadingReadings() {
        assertThatThrownBy(() -> service.subscribe(null,OWNER_ID,TemperatureUnit.CELSIUS,SESSION_ID))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("sensorId must not be null");

        assertThatThrownBy(() -> service.subscribe(SENSOR_ID,null,TemperatureUnit.CELSIUS,SESSION_ID))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("ownerId must not be null");

        verifyNoInteractions(readingService);
        assertThat(emitters).isEmpty();
        assertThat(executor.pendingTasks()).isZero();
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void sendsTheCapturedInitialSnapshotAsynchronouslyAndDefaultsToCelsius(boolean empty) {
        RecentSensorReadingsDTO expected = empty
                ? new RecentSensorReadingsDTO(SENSOR_ID,List.of())
                : initialSnapshot;

        doReturn(expected).when(readingService)
                .getRecentReadingsSnapshot(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS);

        RecordingSseEmitter emitter =
                (RecordingSseEmitter) service.subscribe(SENSOR_ID,OWNER_ID,null,SESSION_ID);

        assertThat(emitter.getTimeout()).isEqualTo(CONNECTION_LIFETIME.toMillis());
        assertThat(emitter.events()).isEmpty();
        assertThat(emitter.completionCalls()).isZero();
        assertThat(executor.pendingTasks()).isEqualTo(1);

        executor.runNext();

        assertThat(emitter.events()).hasSize(1);
        assertReading(emitter.events().getFirst(),expected);
        assertThat(executor.pendingTasks()).isZero();

        verify(readingService).getRecentReadingsSnapshot(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS);
    }



    @ParameterizedTest
    @EnumSource(TemperatureUnit.class)
    void forwardsTheSelectedTemperatureUnitToInitialAndSubsequentQueries(TemperatureUnit unit) {
        doReturn(initialSnapshot).when(readingService)
                .getRecentReadingsSnapshot(SENSOR_ID,OWNER_ID,unit);

        RecordingSseEmitter emitter =
                (RecordingSseEmitter) service.subscribe(SENSOR_ID,OWNER_ID,unit,SESSION_ID);

        executor.runNext();
        refreshAfter(REFRESH_INTERVAL);

        verify(readingService,times(2)).getRecentReadingsSnapshot(SENSOR_ID,OWNER_ID,unit);
        assertThat(emitter.events()).hasSize(1);
        assertReading(emitter.events().getFirst(),initialSnapshot);
    }



    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ","missing","expired","foreign","other-principal"})
    void rejectsSessionsThatAreNotActiveAndOwnedByTheRequestedUser(String sessionId) {
        registerSession("expired",OWNER_ID);
        sessionRegistry.getSessionInformation("expired").expireNow();
        registerSession("foreign",43L);
        sessionRegistry.registerNewSession("other-principal","owner@example.com");

        assertThatThrownBy(() -> service.subscribe(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS,sessionId))
                .isExactlyInstanceOf(SensorReadingStreamSessionExpiredException.class);

        verifyNoInteractions(readingService);
        assertThat(emitters).isEmpty();
        assertThat(executor.pendingTasks()).isZero();
    }



    @Test
    void rechecksSessionAfterLoadingTheInitialSnapshot() {
        doAnswer(invocation -> {
            sessionRegistry.getSessionInformation(SESSION_ID).expireNow();
            return initialSnapshot;
        }).when(readingService).getRecentReadingsSnapshot(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS);

        assertThatThrownBy(this::subscribe)
                .isExactlyInstanceOf(SensorReadingStreamSessionExpiredException.class);

        assertThat(emitters).isEmpty();
        assertThat(executor.pendingTasks()).isZero();
        assertSlotReleased();
    }



    @Test
    void detachesWhenSessionExpiresDuringEmitterCreation() {
        service = new SensorReadingStreamService(
                readingService,policy(1),sessionRegistry,executor,nanoTime::get,timeout -> {
            RecordingSseEmitter emitter = createEmitter(timeout);
            sessionRegistry.getSessionInformation(SESSION_ID).expireNow();
            return emitter;
        });

        assertThatThrownBy(this::subscribe)
                .isExactlyInstanceOf(SensorReadingStreamSessionExpiredException.class);

        assertThat(emitters).hasSize(1);
        assertThat(executor.pendingTasks()).isEqualTo(1);

        executor.runAll();

        assertThat(emitters.getFirst().events()).isEmpty();
        assertThat(emitters.getFirst().completionCalls()).isEqualTo(1);
    }



    @Test
    void rejectsCapacityOverflowBeforeLoadingAnotherSnapshotAndReusesReleasedCapacity() {
        RecordingSseEmitter first = subscribe();

        assertThatThrownBy(this::subscribe)
                .isExactlyInstanceOf(SensorReadingStreamUnavailableException.class);

        verify(readingService).getRecentReadingsSnapshot(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS);
        assertThat(emitters).hasSize(1);
        assertThat(executor.pendingTasks()).isEqualTo(1);

        first.containerCompletion();

        assertThat(executor.pendingTasks()).isZero();
        assertSlotReleased();
    }



    @Test
    void rechecksCapacityAfterTheInitialSnapshotQuery() {
        Long competingSensorId = 101L;
        RecentSensorReadingsDTO competingSnapshot = new RecentSensorReadingsDTO(competingSensorId,List.of());

        doReturn(competingSnapshot).when(readingService)
                .getRecentReadingsSnapshot(competingSensorId,OWNER_ID,TemperatureUnit.CELSIUS);

        doAnswer(invocation -> {
            service.subscribe(competingSensorId,OWNER_ID,TemperatureUnit.CELSIUS,SESSION_ID);
            return initialSnapshot;
        }).when(readingService).getRecentReadingsSnapshot(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS);

        assertThatThrownBy(this::subscribe)
                .isExactlyInstanceOf(SensorReadingStreamUnavailableException.class);

        assertThat(executor.pendingTasks()).isEqualTo(1);

        executor.runNext();

        assertThat(emitters.getFirst().events()).hasSize(1);
        assertReading(emitters.getFirst().events().getFirst(),competingSnapshot);
    }



    @ParameterizedTest
    @ValueSource(longs = {0L,-1_000_000_000L,Long.MAX_VALUE})
    void schedulesRefreshAtTheExactBoundaryWithoutDuplicatingQueuedWork(long origin) {
        nanoTime.set(origin);
        RecordingSseEmitter emitter = subscribe();

        service.maintainStreams();
        service.maintainStreams();

        assertThat(executor.pendingTasks()).isEqualTo(1);
        executor.runNext();

        advance(REFRESH_INTERVAL.minusNanos(1));
        service.maintainStreams();

        assertThat(executor.pendingTasks()).isZero();

        advance(Duration.ofNanos(1));
        service.maintainStreams();
        service.maintainStreams();

        assertThat(executor.pendingTasks()).isEqualTo(1);
        executor.runNext();

        assertThat(emitter.events()).hasSize(1);
        verify(readingService,times(2)).getRecentReadingsSnapshot(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS);
    }



    @Test
    void measuresTheNextRefreshIntervalFromWorkCompletion() {
        subscribe();
        executor.runNext();

        doAnswer(invocation -> {
            advance(Duration.ofSeconds(3));
            return initialSnapshot;
        }).when(readingService).getRecentReadingsSnapshot(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS);

        refreshAfter(REFRESH_INTERVAL);

        advance(REFRESH_INTERVAL.minusNanos(1));
        service.maintainStreams();

        assertThat(executor.pendingTasks()).isZero();

        advance(Duration.ofNanos(1));
        service.maintainStreams();

        assertThat(executor.pendingTasks()).isEqualTo(1);
        verify(readingService,times(2)).getRecentReadingsSnapshot(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS);
    }



    @Test
    void suppressesEqualSnapshotsAndSendsChangedSnapshots() {
        RecentSensorReadingsDTO equalSnapshot = snapshot(20);
        RecentSensorReadingsDTO changedSnapshot = snapshot(21);

        assertThat(equalSnapshot).isEqualTo(initialSnapshot).isNotSameAs(initialSnapshot);

        when(readingService.getRecentReadingsSnapshot(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS))
                .thenReturn(initialSnapshot,equalSnapshot,changedSnapshot);

        RecordingSseEmitter emitter = subscribe();
        executor.runNext();

        refreshAfter(REFRESH_INTERVAL);

        assertThat(emitter.events()).hasSize(1);

        refreshAfter(REFRESH_INTERVAL);

        assertThat(emitter.events()).hasSize(2);
        assertReading(emitter.events().get(0),initialSnapshot);
        assertReading(emitter.events().get(1),changedSnapshot);
    }



    @ParameterizedTest
    @ValueSource(longs = {-1,0})
    void sendsHeartbeatOnlyWhenItsIntervalHasElapsed(long additionalNanos) {
        RecordingSseEmitter emitter = subscribe();
        executor.runNext();

        refreshAfter(HEARTBEAT_INTERVAL.plusNanos(additionalNanos));

        if (additionalNanos < 0) {
            assertThat(emitter.events()).hasSize(1);
        } else {
            assertThat(emitter.events()).hasSize(2);
            assertHeartbeat(emitter.events().getLast());
        }

        assertThat(emitter.completionCalls()).isZero();
    }



    @Test
    void changedSnapshotsAndHeartbeatsBothResetTheHeartbeatInterval() {
        RecentSensorReadingsDTO changedSnapshot = snapshot(21);

        when(readingService.getRecentReadingsSnapshot(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS))
                .thenReturn(initialSnapshot,changedSnapshot);

        RecordingSseEmitter emitter = subscribe();
        executor.runNext();

        refreshAfter(Duration.ofSeconds(2));

        assertThat(emitter.events()).hasSize(2);
        assertReading(emitter.events().getLast(),changedSnapshot);

        refreshAfter(Duration.ofSeconds(4));
        assertThat(emitter.events()).hasSize(2);

        refreshAfter(Duration.ofSeconds(2));
        assertThat(emitter.events()).hasSize(3);
        assertHeartbeat(emitter.events().getLast());

        refreshAfter(Duration.ofSeconds(4));
        assertThat(emitter.events()).hasSize(3);

        refreshAfter(Duration.ofSeconds(2));
        assertThat(emitter.events()).hasSize(4);
        assertHeartbeat(emitter.events().getLast());
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void expiresQueuedWorkAtTheExactTimeoutInMaintenanceOrTheWorker(boolean useMaintenance) {
        RecordingSseEmitter emitter = subscribe();
        Future<?> queuedTask = (Future<?>) executor.nextTask();

        advance(WORK_TIMEOUT.minusNanos(1));
        service.maintainStreams();

        assertThat(queuedTask.isCancelled()).isFalse();
        assertThat(executor.pendingTasks()).isEqualTo(1);
        assertThat(emitter.completionCalls()).isZero();

        advance(Duration.ofNanos(1));

        if (useMaintenance) {
            service.maintainStreams();
        } else {
            executor.runNext();
        }

        assertThat(queuedTask.isCancelled()).isTrue();
        assertThat(executor.pendingTasks()).isEqualTo(1);

        executor.runAll();

        assertThat(emitter.events()).isEmpty();
        assertThat(emitter.completionCalls()).isEqualTo(1);
        verify(readingService).getRecentReadingsSnapshot(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS);
        assertSlotReleased();
    }



    @Test
    void expiresConnectionAtItsExactLifetimeEvenBeforeTheNextRefreshIsDue() {
        RecordingSseEmitter emitter = subscribe();
        executor.runNext();

        refreshAfter(CONNECTION_LIFETIME.minusNanos(1));

        assertThat(emitter.completionCalls()).isZero();
        verify(readingService,times(2)).getRecentReadingsSnapshot(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS);

        advance(Duration.ofNanos(1));
        service.maintainStreams();

        assertThat(executor.pendingTasks()).isEqualTo(1);
        assertThat(emitter.completionCalls()).isZero();

        executor.runAll();
        service.maintainStreams();

        assertThat(emitter.completionCalls()).isEqualTo(1);
        assertThat(executor.pendingTasks()).isZero();
        assertThat(emitter.events()).hasSize(2);
        assertHeartbeat(emitter.events().getLast());
        verify(readingService,times(2)).getRecentReadingsSnapshot(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS);
        assertSlotReleased();
    }



    @Test
    void cancelsRunningWorkAndDefersCompletionUntilTheWorkerActuallyExits() throws Exception {
        RecordingSseEmitter emitter = subscribe();
        executor.runNext();

        CountDownLatch enteredQuery = new CountDownLatch(1);
        CompletableFuture<RecentSensorReadingsDTO> releaseQuery = new CompletableFuture<>();
        RecentSensorReadingsDTO changedSnapshot = snapshot(21);

        doAnswer(invocation -> {
            enteredQuery.countDown();
            // join deliberately models a dependency that does not stop on interruption.
            return releaseQuery.join();
        }).when(readingService).getRecentReadingsSnapshot(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS);

        advance(REFRESH_INTERVAL);
        service.maintainStreams();

        Future<?> refreshTask = (Future<?>) executor.nextTask();
        FutureTask<Void> driver = new FutureTask<>(() -> {
            executor.runNext();
            return null;
        });
        Thread worker = Thread.ofPlatform().daemon(true).start(driver);

        try {
            assertThat(enteredQuery.await(5,TimeUnit.SECONDS)).isTrue();

            advance(WORK_TIMEOUT.minusNanos(1));
            service.maintainStreams();

            assertThat(refreshTask.isCancelled()).isFalse();
            assertThat(executor.pendingTasks()).isZero();

            advance(Duration.ofNanos(1));
            service.maintainStreams();

            assertThat(refreshTask.isCancelled()).isTrue();
            assertThat(emitter.completionCalls()).isZero();
            assertThat(executor.pendingTasks()).isZero();
            assertThat(emitter.events()).hasSize(1);

            releaseQuery.complete(changedSnapshot);
            driver.get(5,TimeUnit.SECONDS);

            assertThat(executor.pendingTasks()).isEqualTo(1);
            assertThat(emitter.completionCalls()).isZero();
        } finally {
            releaseQuery.complete(changedSnapshot);
            worker.join(5000);
            assertThat(worker.isAlive()).isFalse();
        }

        executor.runAll();

        assertThat(emitter.events()).hasSize(1);
        assertReading(emitter.events().getFirst(),initialSnapshot);
        assertThat(emitter.completionCalls()).isEqualTo(1);
        assertSlotReleased();
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void closesStreamsWhenTheirSessionIsRemovedOrMarkedExpired(boolean expire) {
        RecordingSseEmitter emitter = subscribe();
        executor.runNext();

        if (expire) {
            sessionRegistry.getSessionInformation(SESSION_ID).expireNow();
        } else {
            sessionRegistry.removeSessionInformation(SESSION_ID);
        }

        service.maintainStreams();

        assertThat(emitter.completionCalls()).isZero();
        assertThat(executor.pendingTasks()).isEqualTo(1);

        executor.runAll();

        assertThat(emitter.events()).hasSize(2);
        assertTerminal(emitter,"SESSION_EXPIRED");
        verify(readingService).getRecentReadingsSnapshot(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS);
        assertSlotReleased();
    }



    @Test
    void rechecksSessionBeforeTheInitialWorkerSendsAnything() {
        RecordingSseEmitter emitter = subscribe();
        sessionRegistry.getSessionInformation(SESSION_ID).expireNow();

        executor.runAll();

        assertThat(emitter.events()).hasSize(1);
        assertTerminal(emitter,"SESSION_EXPIRED");
        verify(readingService).getRecentReadingsSnapshot(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS);
        assertSlotReleased();
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void prioritizesSessionExpiryWhenItOccursDuringARefreshQuery(boolean sensorDisappears) {
        RecordingSseEmitter emitter = subscribe();
        executor.runNext();

        doAnswer(invocation -> {
            sessionRegistry.getSessionInformation(SESSION_ID).expireNow();

            if (sensorDisappears) {
                throw new SensorNotFoundException();
            }

            return snapshot(21);
        }).when(readingService).getRecentReadingsSnapshot(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS);

        refreshAfter(REFRESH_INTERVAL);

        assertThat(emitter.events()).hasSize(2);
        assertReading(emitter.events().getFirst(),initialSnapshot);
        assertTerminal(emitter,"SESSION_EXPIRED");
        assertSlotReleased();
    }



    @Test
    void closesAllStreamsForTheExactSessionAndPreservesOtherSessions() {
        service = newService(4);
        registerSession("same-owner-other-session",OWNER_ID);
        registerSession("other-owner-session",43L);

        doReturn(initialSnapshot).when(readingService)
                .getRecentReadingsSnapshot(SENSOR_ID,43L,TemperatureUnit.CELSIUS);

        RecordingSseEmitter first = subscribe();
        RecordingSseEmitter second = subscribe();
        RecordingSseEmitter sameOwnerOtherSession = (RecordingSseEmitter) service.subscribe(
                SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS,"same-owner-other-session");
        RecordingSseEmitter otherOwner = (RecordingSseEmitter) service.subscribe(
                SENSOR_ID,43L,TemperatureUnit.CELSIUS,"other-owner-session");

        service.closeSessionStreams(SESSION_ID);
        executor.runAll();

        assertThat(first.events()).hasSize(1);
        assertThat(second.events()).hasSize(1);
        assertTerminal(first,"SESSION_EXPIRED");
        assertTerminal(second,"SESSION_EXPIRED");

        assertThat(sameOwnerOtherSession.events()).hasSize(1);
        assertThat(otherOwner.events()).hasSize(1);
        assertReading(sameOwnerOtherSession.events().getFirst(),initialSnapshot);
        assertReading(otherOwner.events().getFirst(),initialSnapshot);
        assertThat(sameOwnerOtherSession.completionCalls()).isZero();
        assertThat(otherOwner.completionCalls()).isZero();

        service.closeSessionStreams(SESSION_ID);
        service.closeSessionStreams("unknown-session");
        service.closeSessionStreams(null);
        executor.runAll();

        assertThat(first.completionCalls()).isEqualTo(1);
        assertThat(second.completionCalls()).isEqualTo(1);
        assertThat(sameOwnerOtherSession.completionCalls()).isZero();
        assertThat(otherOwner.completionCalls()).isZero();
    }



    @Test
    void propagatesInitialSensorLookupFailureWithoutRegisteringAStream() {
        when(readingService.getRecentReadingsSnapshot(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS))
                .thenThrow(new SensorNotFoundException());

        assertThatThrownBy(this::subscribe).isExactlyInstanceOf(SensorNotFoundException.class);

        assertThat(emitters).isEmpty();
        assertThat(executor.pendingTasks()).isZero();
        assertSlotReleased();
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void closesOnRefreshFailureAndReportsOnlyTheExpectedSensorNotFoundReason(boolean sensorNotFound) {
        RecordingSseEmitter emitter = subscribe();
        executor.runNext();

        RuntimeException failure = sensorNotFound
                ? new SensorNotFoundException()
                : new IllegalStateException("Snapshot lookup failed");

        when(readingService.getRecentReadingsSnapshot(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS))
                .thenThrow(failure);

        assertThatCode(() -> refreshAfter(REFRESH_INTERVAL)).doesNotThrowAnyException();

        if (sensorNotFound) {
            assertThat(emitter.events()).hasSize(2);
            assertTerminal(emitter,"SENSOR_NOT_FOUND");
        } else {
            assertThat(emitter.events()).hasSize(1);
            assertThat(emitter.completionCalls()).isEqualTo(1);
        }

        assertSlotReleased();
    }



    @ParameterizedTest
    @ValueSource(strings = {"completion","timeout","error"})
    void containerCallbacksCancelQueuedWorkWithoutRequestingApplicationCompletion(String callback) {
        RecordingSseEmitter emitter = subscribe();
        Future<?> queuedTask = (Future<?>) executor.nextTask();

        fireContainerCallback(emitter,callback);
        fireContainerCallback(emitter,callback);
        service.maintainStreams();
        executor.runAll();

        assertThat(queuedTask.isCancelled()).isTrue();
        assertThat(executor.pendingTasks()).isZero();
        assertThat(emitter.events()).isEmpty();
        assertThat(emitter.completionCalls()).isZero();
        assertSlotReleased();
    }



    @Test
    void containerCallbackSuppressesAnAlreadyQueuedApplicationCompletion() {
        RecordingSseEmitter emitter = subscribe();
        executor.runNext();

        service.closeSessionStreams(SESSION_ID);

        assertThat(executor.pendingTasks()).isEqualTo(1);

        emitter.containerTimeout();
        executor.runAll();

        assertThat(emitter.events()).hasSize(1);
        assertThat(emitter.completionCalls()).isZero();
        assertSlotReleased();
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void distinguishesContainerManagedIoFailureFromRuntimeSendFailure(boolean ioFailure) {
        RecordingSseEmitter emitter = subscribe();
        Exception failure = ioFailure
                ? new IOException("Client disconnected")
                : new IllegalStateException("Emitter send failed");

        emitter.failNextSend(failure);

        assertThatCode(executor::runAll).doesNotThrowAnyException();

        assertThat(emitter.events()).isEmpty();
        assertThat(emitter.completionCalls()).isEqualTo(ioFailure ? 0 : 1);
        assertSlotReleased();
    }



    @Test
    void initialExecutorRejectionFailsSubscriptionAndReleasesItsSlot() {
        executor.rejectNextTask();

        assertThatThrownBy(this::subscribe)
                .isExactlyInstanceOf(SensorReadingStreamUnavailableException.class);

        assertThat(emitters).hasSize(1);
        assertThat(executor.pendingTasks()).isEqualTo(1);

        executor.runAll();

        assertThat(emitters.getFirst().events()).isEmpty();
        assertThat(emitters.getFirst().completionCalls()).isEqualTo(1);
        assertSlotReleased();
    }



    @Test
    void refreshExecutorRejectionClosesTheExistingStream() {
        RecordingSseEmitter emitter = subscribe();
        executor.runNext();

        advance(REFRESH_INTERVAL);
        executor.rejectNextTask();

        assertThatCode(service::maintainStreams).doesNotThrowAnyException();

        assertThat(executor.pendingTasks()).isEqualTo(1);
        executor.runAll();

        assertThat(emitter.events()).hasSize(1);
        assertThat(emitter.completionCalls()).isEqualTo(1);
        verify(readingService).getRecentReadingsSnapshot(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS);
        assertSlotReleased();
    }



    @Test
    void completionRejectionStillReleasesCapacityAndDoesNotRetryCompletion() {
        RecordingSseEmitter emitter = subscribe();
        executor.runNext();
        executor.setRejecting(true);

        assertThatCode(() -> service.closeSessionStreams(SESSION_ID)).doesNotThrowAnyException();

        assertThat(executor.pendingTasks()).isZero();
        assertThat(emitter.events()).hasSize(1);
        assertThat(emitter.completionCalls()).isZero();

        executor.setRejecting(false);
        service.maintainStreams();
        executor.runAll();

        assertThat(emitter.events()).hasSize(1);
        assertThat(emitter.completionCalls()).isZero();
        assertSlotReleased();
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void containsTerminalSendFailureAndRespectsContainerManagedIoCleanup(boolean ioFailure) {
        RecordingSseEmitter emitter = subscribe();
        executor.runNext();

        Exception failure = ioFailure
                ? new IOException("Client disconnected before terminal event")
                : new IllegalStateException("Terminal event send failed");

        emitter.failNextSend(failure);
        service.closeSessionStreams(SESSION_ID);

        assertThatCode(executor::runAll).doesNotThrowAnyException();

        assertThat(emitter.events()).hasSize(1);
        assertThat(emitter.completionCalls()).isEqualTo(ioFailure ? 0 : 1);
        assertSlotReleased();
    }



    @Test
    void containsEmitterCompletionFailureWithoutRepeatingCompletion() {
        RecordingSseEmitter emitter = subscribe();
        executor.runNext();
        emitter.failCompletion(new IllegalStateException("Emitter completion failed"));

        service.closeSessionStreams(SESSION_ID);

        assertThatCode(executor::runAll).doesNotThrowAnyException();

        service.closeSessionStreams(SESSION_ID);
        service.maintainStreams();
        executor.runAll();

        assertThat(emitter.events()).hasSize(2);
        assertTerminal(emitter,"SESSION_EXPIRED");
        assertSlotReleased();
    }



    @Test
    void shutdownCompletesIdleAndQueuedStreamsOnceAndRejectsNewSubscriptions() {
        service = newService(2);

        RecordingSseEmitter idle = subscribe();
        executor.runNext();

        RecordingSseEmitter queued = subscribe();
        Future<?> queuedTask = (Future<?>) executor.nextTask();

        service.shutdown();
        service.shutdown();
        service.maintainStreams();

        assertThat(queuedTask.isCancelled()).isTrue();

        executor.runAll();

        assertThat(idle.events()).hasSize(1);
        assertReading(idle.events().getFirst(),initialSnapshot);
        assertThat(queued.events()).isEmpty();
        assertThat(idle.completionCalls()).isEqualTo(1);
        assertThat(queued.completionCalls()).isEqualTo(1);
        assertThat(executor.pendingTasks()).isZero();

        assertThatThrownBy(this::subscribe)
                .isExactlyInstanceOf(SensorReadingStreamUnavailableException.class);

        verify(readingService,times(2)).getRecentReadingsSnapshot(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS);
    }



    private SensorReadingStreamService newService(int maximumSubscriptions) {
        return new SensorReadingStreamService(
                readingService,policy(maximumSubscriptions),sessionRegistry,executor,nanoTime::get,this::createEmitter);
    }



    private SensorReadingStreamPolicy policy(int maximumSubscriptions) {
        return new SensorReadingStreamPolicy(
                REFRESH_INTERVAL,HEARTBEAT_INTERVAL,CONNECTION_LIFETIME,WORK_TIMEOUT,maximumSubscriptions,1,10);
    }



    private RecordingSseEmitter createEmitter(long timeoutMillis) {
        RecordingSseEmitter emitter = new RecordingSseEmitter(timeoutMillis);
        emitters.add(emitter);
        return emitter;
    }



    private RecordingSseEmitter subscribe() {
        return (RecordingSseEmitter) service.subscribe(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS,SESSION_ID);
    }



    private void registerSession(String sessionId,Long ownerId) {
        AppUser owner = user();
        ReflectionTestUtils.setField(owner,"id",ownerId);
        owner.verifyEmail(CREATED_AT);

        AuthenticatedUser principal = new AuthenticatedUser(owner);
        principal.eraseCredentials();
        sessionRegistry.registerNewSession(sessionId,principal);
    }



    private void advance(Duration duration) {
        nanoTime.addAndGet(duration.toNanos());
    }



    private void refreshAfter(Duration duration) {
        advance(duration);
        service.maintainStreams();
        executor.runAll();
    }



    private void assertSlotReleased() {
        registerSession(SESSION_ID,OWNER_ID);
        doReturn(initialSnapshot).when(readingService)
                .getRecentReadingsSnapshot(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS);

        assertThatCode(() -> subscribe()).doesNotThrowAnyException();
    }



    private static RecentSensorReadingsDTO snapshot(double value) {
        SensorReadingSnapshotDTO reading = new SensorReadingSnapshotDTO(
                SensorType.TEMPERATURE,"Window",value,null,"°C","2026-01-15T12:00:00Z","UTC","Z");

        return new RecentSensorReadingsDTO(SENSOR_ID,List.of(reading));
    }



    private static void assertReading(RecordedEvent event,RecentSensorReadingsDTO expected) {
        assertThat(event.framing()).contains("event:readings\n");
        assertThat(event.jsonData()).containsExactly(expected);
    }



    private static void assertHeartbeat(RecordedEvent event) {
        assertThat(event.framing()).isEqualTo(":keepalive\n\n");
        assertThat(event.jsonData()).isEmpty();
    }



    private static void assertTerminal(RecordingSseEmitter emitter,String code) {
        RecordedEvent event = emitter.events().getLast();

        assertThat(event.framing()).contains("event:stream-ended\n");
        assertThat(event.jsonData()).containsExactly(Map.of("code",code));
        assertThat(emitter.completionCalls()).isEqualTo(1);
    }



    private static void fireContainerCallback(RecordingSseEmitter emitter,String callback) {
        switch (callback) {
            case "completion" -> emitter.containerCompletion();
            case "timeout" -> emitter.containerTimeout();
            case "error" -> emitter.containerError(new IOException("Client disconnected"));
            default -> throw new IllegalArgumentException("Unknown container callback: " + callback);
        }
    }
}