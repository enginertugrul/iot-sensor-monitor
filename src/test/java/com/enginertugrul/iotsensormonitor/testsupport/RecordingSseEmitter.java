package com.enginertugrul.iotsensormonitor.testsupport;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class RecordingSseEmitter extends SseEmitter {

    private final List<RecordedEvent> events = new CopyOnWriteArrayList<>();
    private final List<Runnable> completionCallbacks = new ArrayList<>();
    private final List<Runnable> timeoutCallbacks = new ArrayList<>();
    private final List<Consumer<Throwable>> errorCallbacks = new ArrayList<>();
    private final AtomicInteger completionCalls = new AtomicInteger();

    private volatile Exception nextSendFailure;
    private volatile RuntimeException completionFailure;

    public RecordingSseEmitter(long timeoutMillis) {
        super(timeoutMillis);
    }

    @Override
    public void send(SseEventBuilder builder) throws IOException {
        Exception failure = nextSendFailure;
        nextSendFailure = null;

        if (failure instanceof IOException ioException) {
            throw ioException;
        }

        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }

        events.add(new RecordedEvent(List.copyOf(builder.build())));
    }

    @Override
    public void complete() {
        completionCalls.incrementAndGet();

        if (completionFailure != null) {
            throw completionFailure;
        }
    }

    @Override
    public void onCompletion(Runnable callback) {
        completionCallbacks.add(callback);
    }

    @Override
    public void onTimeout(Runnable callback) {
        timeoutCallbacks.add(callback);
    }

    @Override
    public void onError(Consumer<Throwable> callback) {
        errorCallbacks.add(callback);
    }

    public List<RecordedEvent> events() {
        return List.copyOf(events);
    }

    public int completionCalls() {
        return completionCalls.get();
    }

    public void failNextSend(Exception failure) {
        if (!(failure instanceof IOException) && !(failure instanceof RuntimeException)) {
            throw new IllegalArgumentException("Failure must be an IOException or RuntimeException");
        }

        nextSendFailure = failure;
    }

    public void failCompletion(RuntimeException failure) {
        completionFailure = failure;
    }

    public void containerCompletion() {
        completionCallbacks.forEach(Runnable::run);
    }

    public void containerTimeout() {
        timeoutCallbacks.forEach(Runnable::run);
    }

    public void containerError(Throwable failure) {
        errorCallbacks.forEach(callback -> callback.accept(failure));
    }

    public record RecordedEvent(List<DataWithMediaType> parts) {

        public RecordedEvent {
            parts = List.copyOf(parts);
        }

        public String framing() {
            return parts.stream()
                    .filter(part -> !MediaType.APPLICATION_JSON.equals(part.getMediaType()))
                    .map(DataWithMediaType::getData)
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .collect(Collectors.joining());
        }

        public List<Object> jsonData() {
            return parts.stream()
                    .filter(part -> MediaType.APPLICATION_JSON.equals(part.getMediaType()))
                    .map(DataWithMediaType::getData)
                    .toList();
        }
    }
}