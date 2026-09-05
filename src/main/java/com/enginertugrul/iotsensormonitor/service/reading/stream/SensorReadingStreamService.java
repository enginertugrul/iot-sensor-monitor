package com.enginertugrul.iotsensormonitor.service.reading.stream;

import com.enginertugrul.iotsensormonitor.config.SensorReadingStreamConfig;
import com.enginertugrul.iotsensormonitor.dto.reading.RecentSensorReadingsDTO;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.exception.SensorNotFoundException;
import com.enginertugrul.iotsensormonitor.exception.SensorReadingStreamSessionExpiredException;
import com.enginertugrul.iotsensormonitor.exception.SensorReadingStreamUnavailableException;
import com.enginertugrul.iotsensormonitor.security.AuthenticatedUser;
import com.enginertugrul.iotsensormonitor.service.reading.SensorReadingService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.FutureTask;





@Service
public class SensorReadingStreamService {


    private final Logger logger = LoggerFactory.getLogger(SensorReadingStreamService.class);
    private final SensorReadingService readingService;
    private final SensorReadingStreamPolicy policy;
    private final ThreadPoolTaskExecutor streamExecutor;
    private final SessionRegistry sessionRegistry;

    private final Object registryMonitor = new Object();
    private final Map<UUID,Subscription> subscriptions = new LinkedHashMap<>();
    private boolean acceptingSubscriptions = true;


    public SensorReadingStreamService(SensorReadingService readingService,SensorReadingStreamPolicy policy,
                                      SessionRegistry sessionRegistry,
                                      @Qualifier(SensorReadingStreamConfig.STREAM_EXECUTOR) ThreadPoolTaskExecutor streamExecutor) {
        this.readingService = readingService;
        this.policy = policy;
        this.sessionRegistry = sessionRegistry;
        this.streamExecutor = streamExecutor;
    }




    public SseEmitter subscribe(Long sensorId,Long ownerId,TemperatureUnit temperatureUnit,String sessionId) {
        Objects.requireNonNull(sensorId,"sensorId must not be null");
        Objects.requireNonNull(ownerId,"ownerId must not be null");
        requireActiveSession(sessionId,ownerId);

        synchronized (registryMonitor) {
            requireCapacity();
        }

        TemperatureUnit displayUnit = temperatureUnit == null ? TemperatureUnit.CELSIUS : temperatureUnit;
        RecentSensorReadingsDTO initialSnapshot = readingService.getRecentReadingsSnapshot(sensorId,ownerId,displayUnit);
        requireActiveSession(sessionId,ownerId);

        Subscription subscription = new Subscription(sensorId,ownerId,displayUnit,sessionId,initialSnapshot,
                policy.getConnectionLifetime().toMillis());

        subscription.emitter.onCompletion(() -> detach(subscription,false));
        subscription.emitter.onTimeout(() -> detach(subscription,false));
        subscription.emitter.onError(exception -> detach(subscription,false));

        synchronized (registryMonitor) {
            requireCapacity();
            subscriptions.put(subscription.id,subscription);
        }

        try {
            requireActiveSession(sessionId,ownerId);

            if (!enqueueRefresh(subscription)) {
                requireActiveSession(sessionId,ownerId);
                throw new SensorReadingStreamUnavailableException();
            }
        } catch (RuntimeException exception) {
            detach(subscription,true);
            throw exception;
        }

        return subscription.emitter;
    }





    public void maintainStreams() {
        for (Subscription subscription : currentSubscriptions()) {
            if (closeIfSessionExpired(subscription)) {
                continue;
            }

            boolean expired;

            synchronized (subscription) {
                if (subscription.closed) {
                    continue;
                }

                expired = isExpired(subscription,System.nanoTime());
            }

            if (expired) {
                detach(subscription,true);
            } else {
                enqueueRefresh(subscription);
            }
        }
    }





    @PreDestroy
    public void shutdown() {
        List<Subscription> activeSubscriptions;

        synchronized (registryMonitor) {
            acceptingSubscriptions = false;
            activeSubscriptions = List.copyOf(subscriptions.values());
        }

        activeSubscriptions.forEach(subscription -> detach(subscription,true));
    }




    public void closeSessionStreams(String sessionId) {
        for (Subscription subscription : currentSubscriptions()) {
            if (subscription.sessionId.equals(sessionId)) {
                detach(subscription,true,StreamEndReason.SESSION_EXPIRED);
            }
        }
    }

    private void requireActiveSession(String sessionId,Long ownerId) {
        if (!isSessionActive(sessionId,ownerId)) {
            throw new SensorReadingStreamSessionExpiredException();
        }
    }

    private boolean isSessionActive(String sessionId,Long ownerId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }

        SessionInformation information = sessionRegistry.getSessionInformation(sessionId);

        return information != null
                && !information.isExpired()
                && information.getPrincipal() instanceof AuthenticatedUser authenticatedUser
                && authenticatedUser.getAppUserId().equals(ownerId);
    }

    private boolean closeIfSessionExpired(Subscription subscription) {
        if (isSessionActive(subscription.sessionId,subscription.ownerId)) {
            return false;
        }

        detach(subscription,true,StreamEndReason.SESSION_EXPIRED);
        return true;
    }




    private void requireCapacity() {
        if (!acceptingSubscriptions || subscriptions.size() >= policy.getMaximumSubscriptions()) {
            throw new SensorReadingStreamUnavailableException();
        }
    }



    private List<Subscription> currentSubscriptions() {
        synchronized (registryMonitor) {
            return List.copyOf(subscriptions.values());
        }
    }




    private boolean enqueueRefresh(Subscription subscription) {
        FutureTask<Void> task;

        synchronized (subscription) {
            if (subscription.closed) {
                return false;
            }

            long now = System.nanoTime();

            if (subscription.task != null || now - subscription.nextRefreshAt < 0) {
                return true;
            }

            task = new FutureTask<>(() -> {
                refresh(subscription);
                return null;
            });

            subscription.task = task;
            subscription.taskQueuedAt = now;
        }

        try {
            streamExecutor.execute(task);

            if (task.isCancelled()) {
                streamExecutor.getThreadPoolExecutor().remove(task);
            }
        } catch (TaskRejectedException exception) {
            detach(subscription,true);
            return false;
        }

        synchronized (subscription) {
            return !subscription.closed;
        }
    }




    private void refresh(Subscription subscription) {
        RecentSensorReadingsDTO snapshot;

        synchronized (subscription) {
            if (subscription.closed) {
                return;
            }

            subscription.running = true;
            snapshot = subscription.initialSnapshot;
            subscription.initialSnapshot = null;
        }

        try {
            if (closeIfSessionExpired(subscription)) {
                return;
            }

            if (snapshot == null) {
                snapshot = readingService.getRecentReadingsSnapshot(subscription.sensorId,
                        subscription.ownerId,subscription.temperatureUnit);
            }

            boolean expired;
            boolean changed;
            boolean heartbeatDue;

            synchronized (subscription) {
                if (subscription.closed) {
                    return;
                }

                long now = System.nanoTime();
                expired = isExpired(subscription,now);
                changed = !snapshot.equals(subscription.lastSnapshot);
                heartbeatDue = now - subscription.lastSentAt >= policy.getHeartbeatInterval().toNanos();
            }

            if (closeIfSessionExpired(subscription)) {
                return;
            }

            if (expired) {
                detach(subscription,true);
                return;
            }

            if (changed) {
                subscription.emitter.send(SseEmitter.event().name("readings").data(snapshot,MediaType.APPLICATION_JSON));
            } else if (heartbeatDue) {
                subscription.emitter.send(SseEmitter.event().comment("keepalive"));
            }

            synchronized (subscription) {
                if (!subscription.closed) {
                    subscription.lastSnapshot = snapshot;

                    if (changed || heartbeatDue) {
                        subscription.lastSentAt = System.nanoTime();
                    }
                }
            }
        } catch (IOException exception) {
            detach(subscription,false);
        } catch (SensorNotFoundException exception) {
            if (!closeIfSessionExpired(subscription)) {
                detach(subscription,true,StreamEndReason.SENSOR_NOT_FOUND);
            }
        } catch (RuntimeException exception) {
            logger.warn("Recent reading stream failed. sensorId={}, failureType={}",
                    subscription.sensorId,exception.getClass().getSimpleName());
            detach(subscription,true);
        } finally {
            finishWork(subscription);
        }
    }





    private boolean isExpired(Subscription subscription,long now) {
        boolean lifetimeExpired = now - subscription.createdAt >= policy.getConnectionLifetime().toNanos();
        boolean workExpired = subscription.task != null
                && now - subscription.taskQueuedAt >= policy.getWorkTimeout().toNanos();

        return lifetimeExpired || workExpired;
    }




    private void finishWork(Subscription subscription) {
        boolean complete;

        synchronized (subscription) {
            subscription.running = false;
            subscription.task = null;
            subscription.nextRefreshAt = System.nanoTime() + policy.getRefreshInterval().toNanos();
            complete = subscription.completeOnExit;
            subscription.completeOnExit = false;
        }

        if (complete) {
            enqueueCompletion(subscription);
        }
    }





    private void detach(Subscription subscription,boolean applicationCompletion) {
        detach(subscription,applicationCompletion,null);
    }



    private void detach(Subscription subscription,boolean applicationCompletion,StreamEndReason terminalReason) {
        FutureTask<Void> task;
        boolean completeNow = false;

        synchronized (subscription) {
            if (!applicationCompletion) {
                subscription.containerManaged = true;
                subscription.completeOnExit = false;
                subscription.terminalReason = null;
            }

            if (subscription.closed) {
                return;
            }

            subscription.closed = true;
            subscription.terminalReason = terminalReason;
            subscription.initialSnapshot = null;
            subscription.lastSnapshot = null;
            task = subscription.task;

            if (applicationCompletion && !subscription.containerManaged) {
                subscription.completeOnExit = subscription.running;
                completeNow = !subscription.running;
            }

            if (!subscription.running) {
                subscription.task = null;
            }
        }

        synchronized (registryMonitor) {
            subscriptions.remove(subscription.id);
        }

        if (task != null) {
            task.cancel(true);
            streamExecutor.getThreadPoolExecutor().remove(task);
        }

        if (completeNow) {
            enqueueCompletion(subscription);
        }
    }



    private void enqueueCompletion(Subscription subscription) {
        try {
            streamExecutor.execute(() -> completeQuietly(subscription));
        } catch (TaskRejectedException exception) {
            logger.debug("Recent reading stream completion deferred to container timeout. sensorId={}",
                    subscription.sensorId);
        }
    }




    private void completeQuietly(Subscription subscription) {
        StreamEndReason terminalReason;

        synchronized (subscription) {
            if (subscription.containerManaged) {
                return;
            }

            terminalReason = subscription.terminalReason;
            subscription.terminalReason = null;
        }

        if (terminalReason != null) {
            try {
                subscription.emitter.send(SseEmitter.event().name("stream-ended")
                        .data(Map.of("code",terminalReason.name()),MediaType.APPLICATION_JSON));
            } catch (IOException exception) {
                detach(subscription,false);
                return;
            } catch (RuntimeException exception) {
                logger.debug("Recent reading stream terminal event failed. sensorId={}, failureType={}",
                        subscription.sensorId,exception.getClass().getSimpleName());
            }
        }

        try {
            subscription.emitter.complete();
        } catch (RuntimeException exception) {
            logger.debug("Recent reading stream completion failed. sensorId={}, failureType={}",
                    subscription.sensorId,exception.getClass().getSimpleName());
        }
    }



    private enum StreamEndReason {
        SESSION_EXPIRED,
        SENSOR_NOT_FOUND
    }




    private static final class Subscription {

        private final UUID id = UUID.randomUUID();
        private final Long sensorId;
        private final Long ownerId;
        private final TemperatureUnit temperatureUnit;
        private final String sessionId;
        private StreamEndReason terminalReason;
        private final SseEmitter emitter;
        private final long createdAt = System.nanoTime();

        private RecentSensorReadingsDTO initialSnapshot;
        private RecentSensorReadingsDTO lastSnapshot;
        private FutureTask<Void> task;
        private long taskQueuedAt;
        private long nextRefreshAt = createdAt;
        private long lastSentAt = createdAt;
        private boolean running;
        private boolean closed;
        private boolean containerManaged;
        private boolean completeOnExit;


        private Subscription(Long sensorId,Long ownerId,TemperatureUnit temperatureUnit,String sessionId,
                             RecentSensorReadingsDTO initialSnapshot,long timeoutMillis) {
            this.sensorId = sensorId;
            this.ownerId = ownerId;
            this.temperatureUnit = temperatureUnit;
            this.sessionId = sessionId;
            this.initialSnapshot = initialSnapshot;
            this.emitter = new SseEmitter(timeoutMillis);
        }
    }


}