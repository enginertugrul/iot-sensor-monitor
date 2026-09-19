package com.enginertugrul.iotsensormonitor.service.reading.stream;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@FunctionalInterface
public interface SensorReadingStreamEmitterFactory {

    SseEmitter create(long timeoutMillis);
}