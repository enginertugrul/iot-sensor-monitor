package com.enginertugrul.iotsensormonitor.controller;

import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.exception.SensorReadingStreamSessionExpiredException;
import com.enginertugrul.iotsensormonitor.security.AuthenticatedUser;
import com.enginertugrul.iotsensormonitor.service.reading.stream.SensorReadingStreamService;
import com.enginertugrul.iotsensormonitor.service.user.AppUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class SensorReadingStreamController {

    private final SensorReadingStreamService streamService;
    private final AppUserService appUserService;

    public SensorReadingStreamController(SensorReadingStreamService streamService,AppUserService appUserService) {
        this.streamService = streamService;
        this.appUserService = appUserService;
    }

    @GetMapping(path = "/api/sensors/{sensorId}/readings/stream",produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> streamReadings(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable("sensorId") Long sensorId,
            HttpServletRequest request
    ) {
        HttpSession session = request.getSession(false);

        if (authenticatedUser == null || session == null) {
            throw new SensorReadingStreamSessionExpiredException();
        }

        Long ownerId = authenticatedUser.getAppUserId();
        String sessionId = session.getId();
        TemperatureUnit temperatureUnit = appUserService.getPreferredTemperatureUnit(ownerId);
        SseEmitter emitter = streamService.subscribe(sensorId,ownerId,temperatureUnit,sessionId);

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noStore().noTransform())
                .header("X-Accel-Buffering","no")
                .body(emitter);
    }
}