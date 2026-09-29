package com.enginertugrul.iotsensormonitor.testsupport;

import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ControlledStreamExecutor extends ThreadPoolTaskExecutor {

    private final ThreadPoolExecutor controlledPool =
            new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,new LinkedBlockingQueue<>());

    private boolean rejecting;
    private boolean rejectNext;

    @Override
    public synchronized void execute(Runnable task) {
        if (rejecting || rejectNext || controlledPool.isShutdown()) {
            rejectNext = false;
            throw new TaskRejectedException("Controlled stream executor rejected the task");
        }

        controlledPool.getQueue().add(task);
    }

    @Override
    public ThreadPoolExecutor getThreadPoolExecutor() {
        return controlledPool;
    }

    public synchronized void rejectNextTask() {
        rejectNext = true;
    }

    public synchronized void setRejecting(boolean rejecting) {
        this.rejecting = rejecting;
    }

    public int pendingTasks() {
        return controlledPool.getQueue().size();
    }

    public Runnable nextTask() {
        return controlledPool.getQueue().element();
    }

    public void runNext() {
        Runnable task = controlledPool.getQueue().remove();
        boolean previouslyInterrupted = Thread.interrupted();

        try {
            task.run();

            if (task instanceof Future<?> future && !future.isCancelled()) {
                future.get(5,TimeUnit.SECONDS);
            }
        } catch (ExecutionException exception) {
            throw new AssertionError("Controlled stream task failed",exception.getCause());
        } catch (InterruptedException | TimeoutException exception) {
            throw new AssertionError("Controlled stream task did not finish normally",exception);
        } finally {
            // Service cleanup can cancel and interrupt the task currently running here.
            Thread.interrupted();

            if (previouslyInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void runAll() {
        for (int count = 0; count < 1000 && pendingTasks() > 0; count++) {
            runNext();
        }

        if (pendingTasks() > 0) {
            throw new AssertionError("Controlled stream executor did not drain");
        }
    }

    @Override
    public void shutdown() {
        controlledPool.shutdownNow();
    }
}