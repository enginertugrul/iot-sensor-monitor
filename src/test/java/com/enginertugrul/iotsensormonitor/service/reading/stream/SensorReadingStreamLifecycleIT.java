package com.enginertugrul.iotsensormonitor.service.reading.stream;

import com.enginertugrul.iotsensormonitor.config.SchedulingConfig;
import com.enginertugrul.iotsensormonitor.config.SensorReadingStreamConfig;
import com.enginertugrul.iotsensormonitor.dto.reading.RecentSensorReadingsDTO;
import com.enginertugrul.iotsensormonitor.dto.reading.SensorReadingSnapshotDTO;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.exception.SensorReadingStreamUnavailableException;
import com.enginertugrul.iotsensormonitor.scheduler.SensorReadingStreamScheduler;
import com.enginertugrul.iotsensormonitor.security.AuthenticatedUser;
import com.enginertugrul.iotsensormonitor.service.reading.SensorReadingService;
import com.enginertugrul.iotsensormonitor.testsupport.RecordingSseEmitter;
import com.enginertugrul.iotsensormonitor.testsupport.RecordingSseEmitter.RecordedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.support.DefaultLifecycleProcessor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static com.enginertugrul.iotsensormonitor.config.SensorReadingStreamConfig.STREAM_EXECUTOR;
import static com.enginertugrul.iotsensormonitor.config.SensorReadingStreamConfig.STREAM_NANO_TIME_SOURCE;
import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.context.support.AbstractApplicationContext.LIFECYCLE_PROCESSOR_BEAN_NAME;



class SensorReadingStreamLifecycleIT {

    private static final Long SENSOR_ID = 100L;
    private static final Long OWNER_ID = 42L;
    private static final String SESSION_ID = "stream-lifecycle-session";

    private static final Duration REFRESH_INTERVAL = Duration.ofSeconds(2);
    private static final Duration CONNECTION_LIFETIME = Duration.ofSeconds(30);
    private static final long WAIT_SECONDS = 10;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class,SensorReadingStreamConfig.class,LifecycleTestConfiguration.class);



    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "true")
    void runsScheduledRefreshAndExpiryWhenEnabledExplicitlyOrByDefault(String enabled) {
        ApplicationContextRunner runner = enabled == null
                ? contextRunner
                : contextRunner.withPropertyValues("app.scheduling.enabled=" + enabled);

        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(SensorReadingStreamScheduler.class);
            assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);

            SensorReadingService readingService = context.getBean(SensorReadingService.class);
            SensorReadingStreamService streamService = context.getBean(SensorReadingStreamService.class);
            ThreadPoolTaskExecutor executor = context.getBean(STREAM_EXECUTOR,ThreadPoolTaskExecutor.class);
            AtomicLong nanoTime = context.getBean(AtomicLong.class);
            RecentSensorReadingsDTO initial = snapshot(20);
            RecentSensorReadingsDTO updated = snapshot(21);

            when(readingService.getRecentReadingsSnapshot(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS))
                    .thenReturn(initial);

            RecordingSseEmitter emitter =
                    (RecordingSseEmitter) streamService.subscribe(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS,SESSION_ID);
            awaitWorker(executor);

            assertThat(emitter.events()).hasSize(1);
            assertReading(emitter.events().getFirst(),initial);
            assertThat(emitter.completionCalls()).isZero();

            CountDownLatch refreshed = new CountDownLatch(1);
            when(readingService.getRecentReadingsSnapshot(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS))
                    .thenAnswer(invocation -> {
                        refreshed.countDown();
                        return updated;
                    });

            nanoTime.addAndGet(REFRESH_INTERVAL.toNanos());

            awaitSignal(refreshed,"scheduled maintenance refreshes the stream");
            awaitWorker(executor);

            assertThat(emitter.events()).hasSize(2);
            assertReading(emitter.events().getLast(),updated);
            verify(readingService,times(2)).getRecentReadingsSnapshot(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS);

            nanoTime.set(CONNECTION_LIFETIME.toNanos());

            await().atMost(Duration.ofSeconds(WAIT_SECONDS)).untilAsserted(() ->
                    assertThat(emitter.completionCalls()).isEqualTo(1));
            awaitWorker(executor);

            assertThat(emitter.events()).hasSize(2);
            verifyNoMoreInteractions(readingService);
        });
    }



    @Test
    void keepsInitialDeliveryAvailableWhenSchedulingIsDisabled() {
        contextRunner.withPropertyValues("app.scheduling.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(SchedulingConfig.class);
            assertThat(context).doesNotHaveBean(SensorReadingStreamScheduler.class);
            assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);

            SensorReadingService readingService = context.getBean(SensorReadingService.class);
            SensorReadingStreamService streamService = context.getBean(SensorReadingStreamService.class);
            ThreadPoolTaskExecutor executor = context.getBean(STREAM_EXECUTOR,ThreadPoolTaskExecutor.class);
            ThreadPoolExecutor workerPool = executor.getThreadPoolExecutor();
            RecentSensorReadingsDTO initial = snapshot(20);

            when(readingService.getRecentReadingsSnapshot(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS))
                    .thenReturn(initial);

            RecordingSseEmitter emitter =
                    (RecordingSseEmitter) streamService.subscribe(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS,SESSION_ID);
            awaitWorker(executor);

            assertThat(emitter.events()).hasSize(1);
            assertReading(emitter.events().getFirst(),initial);
            assertThat(emitter.completionCalls()).isZero();
            verify(readingService).getRecentReadingsSnapshot(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS);
            verifyNoMoreInteractions(readingService);

            context.close();

            assertStopped(workerPool);
            assertThat(workerPool.getQueue()).isEmpty();
        });
    }



    @Test
    void continuesScheduledMaintenanceAfterRuntimeFailure() {
        SensorReadingStreamService streamService = mock(SensorReadingStreamService.class);
        CountDownLatch recovered = new CountDownLatch(1);

        doThrow(new IllegalStateException("First maintenance attempt failed"))
                .doAnswer(invocation -> {
                    recovered.countDown();
                    return null;
                })
                .when(streamService).maintainStreams();

        new ApplicationContextRunner()
                .withUserConfiguration(SchedulingConfig.class)
                .withPropertyValues("app.scheduling.enabled=true")
                .withBean(SensorReadingStreamService.class,() -> streamService)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SensorReadingStreamScheduler.class);

                    ScheduledThreadPoolExecutor timer = timer(context.getBean(SensorReadingStreamScheduler.class));

                    awaitSignal(recovered,"the scheduler runs again after a maintenance failure");
                    verify(streamService,atLeast(2)).maintainStreams();

                    context.close();

                    assertStopped(timer);
                    assertThat(timer.getQueue()).isEmpty();
                });
    }



    @Test
    void closingTheContextDetachesStreamsAndStopsBothExecutors() {
        contextRunner.withPropertyValues("app.scheduling.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();

            SensorReadingService readingService = context.getBean(SensorReadingService.class);
            SensorReadingStreamService streamService = context.getBean(SensorReadingStreamService.class);
            ThreadPoolTaskExecutor executor = context.getBean(STREAM_EXECUTOR,ThreadPoolTaskExecutor.class);
            ThreadPoolExecutor workerPool = executor.getThreadPoolExecutor();
            ScheduledThreadPoolExecutor timer = timer(context.getBean(SensorReadingStreamScheduler.class));

            when(readingService.getRecentReadingsSnapshot(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS))
                    .thenReturn(snapshot(20));

            RecordingSseEmitter emitter =
                    (RecordingSseEmitter) streamService.subscribe(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS,SESSION_ID);
            awaitWorker(executor);

            assertThat(emitter.events()).hasSize(1);
            assertThat(emitter.completionCalls()).isZero();
            assertThat((Map<?,?>) ReflectionTestUtils.getField(streamService,"subscriptions")).hasSize(1);
            assertThat(timer.isShutdown()).isFalse();
            assertThat(workerPool.isShutdown()).isFalse();

            context.close();

            assertThat(context.isActive()).isFalse();
            assertStopped(timer);
            assertStopped(workerPool);
            assertThat(timer.getQueue()).isEmpty();
            assertThat(workerPool.getQueue()).isEmpty();
            assertThat((Map<?,?>) ReflectionTestUtils.getField(streamService,"subscriptions")).isEmpty();

            assertThatThrownBy(() -> streamService.subscribe(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS,SESSION_ID))
                    .isExactlyInstanceOf(SensorReadingStreamUnavailableException.class);
            assertThatThrownBy(() -> executor.execute(() -> {}))
                    .isInstanceOf(TaskRejectedException.class);

            verify(readingService).getRecentReadingsSnapshot(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS);
            verifyNoMoreInteractions(readingService);
        });
    }



    @Test
    void closingTheContextInterruptsRunningWorkAndCancelsQueuedWork() {
        contextRunner.withPropertyValues("app.scheduling.enabled=true")
                .withBean(LIFECYCLE_PROCESSOR_BEAN_NAME,DefaultLifecycleProcessor.class,() -> {
                    DefaultLifecycleProcessor processor = new DefaultLifecycleProcessor();
                    processor.setTimeoutPerShutdownPhase(100);
                    return processor;
                })
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    ThreadPoolTaskExecutor executor = context.getBean(STREAM_EXECUTOR,ThreadPoolTaskExecutor.class);
                    ThreadPoolExecutor workerPool = executor.getThreadPoolExecutor();
                    ScheduledThreadPoolExecutor timer = timer(context.getBean(SensorReadingStreamScheduler.class));
                    CountDownLatch started = new CountDownLatch(1);
                    CountDownLatch release = new CountDownLatch(1);
                    CountDownLatch interrupted = new CountDownLatch(1);
                    AtomicBoolean queuedWorkRan = new AtomicBoolean();

                    try {
                        Future<?> running = executor.submit(() -> {
                            started.countDown();

                            try {
                                release.await();
                            } catch (InterruptedException exception) {
                                interrupted.countDown();
                                Thread.currentThread().interrupt();
                            }
                        });

                        awaitSignal(started,"the worker starts before more work is queued");

                        Future<?> queued = executor.submit(() -> queuedWorkRan.set(true));

                        assertThat(workerPool.getQueue()).hasSize(1);

                        context.close();

                        assertStopped(timer);
                        assertStopped(workerPool);
                        assertThat(interrupted.getCount()).isZero();
                        assertThat(running.isDone()).isTrue();
                        assertThat(queued.isCancelled()).isTrue();
                        assertThat(queuedWorkRan).isFalse();
                        assertThat(timer.getQueue()).isEmpty();
                        assertThat(workerPool.getQueue()).isEmpty();
                    } finally {
                        release.countDown();
                    }
                });
    }



    private static void awaitWorker(ThreadPoolTaskExecutor executor) throws Exception {
        // The single worker finishes earlier stream work before this barrier completes.
        executor.submit(() -> {}).get(WAIT_SECONDS,TimeUnit.SECONDS);
    }



    private static void awaitSignal(CountDownLatch signal,String description) throws InterruptedException {
        assertThat(signal.await(WAIT_SECONDS,TimeUnit.SECONDS)).as(description).isTrue();
    }



    private static void assertStopped(ExecutorService executor) throws InterruptedException {
        assertThat(executor.isShutdown()).as("context closure requests executor shutdown").isTrue();
        assertThat(executor.awaitTermination(WAIT_SECONDS,TimeUnit.SECONDS))
                .as("the executor terminates after context closure").isTrue();
    }



    private static ScheduledThreadPoolExecutor timer(SensorReadingStreamScheduler scheduler) {
        return (ScheduledThreadPoolExecutor) ReflectionTestUtils.getField(scheduler,"timer");
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



    @TestConfiguration(proxyBeanMethods = false)
    @Import(SensorReadingStreamService.class)
    static class LifecycleTestConfiguration {

        @Bean
        SensorReadingService readingService() {
            return mock(SensorReadingService.class);
        }

        @Bean
        SensorReadingStreamPolicy streamPolicy() {
            return new SensorReadingStreamPolicy(
                    REFRESH_INTERVAL,Duration.ofSeconds(6),CONNECTION_LIFETIME,Duration.ofSeconds(10),2,1,4);
        }

        @Bean
        SessionRegistry sessionRegistry() {
            AppUser owner = user();
            ReflectionTestUtils.setField(owner,"id",OWNER_ID);
            owner.verifyEmail(CREATED_AT);

            AuthenticatedUser principal = new AuthenticatedUser(owner);
            principal.eraseCredentials();

            SessionRegistryImpl registry = new SessionRegistryImpl();
            registry.registerNewSession(SESSION_ID,principal);
            return registry;
        }

        @Bean
        AtomicLong nanoTime() {
            return new AtomicLong();
        }

        @Bean
        @Primary
        @Qualifier(STREAM_NANO_TIME_SOURCE)
        LongSupplier controlledNanoTimeSource(AtomicLong nanoTime) {
            return nanoTime::get;
        }

        @Bean
        @Primary
        SensorReadingStreamEmitterFactory recordingEmitterFactory() {
            return RecordingSseEmitter::new;
        }
    }
}