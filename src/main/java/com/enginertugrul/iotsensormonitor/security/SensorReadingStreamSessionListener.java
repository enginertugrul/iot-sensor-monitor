package com.enginertugrul.iotsensormonitor.security;

import com.enginertugrul.iotsensormonitor.service.reading.stream.SensorReadingStreamService;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.session.SessionDestroyedEvent;
import org.springframework.stereotype.Component;

@Component
public class SensorReadingStreamSessionListener {

    private final SensorReadingStreamService streamService;

    public SensorReadingStreamSessionListener(SensorReadingStreamService streamService) {
        this.streamService = streamService;
    }

    @EventListener
    public void onSessionDestroyed(SessionDestroyedEvent event) {
        streamService.closeSessionStreams(event.getId());
    }
}