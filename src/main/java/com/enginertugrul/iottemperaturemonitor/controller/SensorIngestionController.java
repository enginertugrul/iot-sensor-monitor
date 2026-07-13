package com.enginertugrul.iottemperaturemonitor.controller;

import com.enginertugrul.iottemperaturemonitor.dto.reading.HumidityReadingRequest;
import com.enginertugrul.iottemperaturemonitor.dto.reading.MotionReadingRequest;
import com.enginertugrul.iottemperaturemonitor.dto.reading.TemperatureReadingRequest;
import com.enginertugrul.iottemperaturemonitor.service.reading.ingestion.HumidityReadingIngestionService;
import com.enginertugrul.iottemperaturemonitor.service.reading.ingestion.MotionReadingIngestionService;
import com.enginertugrul.iottemperaturemonitor.service.reading.ingestion.TemperatureReadingIngestionService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
public class SensorIngestionController {

    private final TemperatureReadingIngestionService temperatureService;
    private final HumidityReadingIngestionService humidityService;
    private final MotionReadingIngestionService motionService;

    public SensorIngestionController(
            TemperatureReadingIngestionService temperatureService,
            HumidityReadingIngestionService humidityService,
            MotionReadingIngestionService motionService
    ) {
        this.temperatureService = temperatureService;
        this.humidityService = humidityService;
        this.motionService = motionService;
    }

    @PostMapping(
            path = {"/readings", "/readings/temperature"},
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE
    )
    public ResponseEntity<Void> receiveTemperature(
            @Valid @ModelAttribute TemperatureReadingRequest request
    ) {
        temperatureService.ingest(
                request.sensorToken(),
                request.celsiusValue()
        );

        return ResponseEntity.ok().build();
    }

    @PostMapping(
            path = "/readings/humidity",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE
    )
    public ResponseEntity<Void> receiveHumidity(
            @Valid @ModelAttribute HumidityReadingRequest request
    ) {
        humidityService.ingest(
                request.sensorToken(),
                request.humidityPercentage()
        );

        return ResponseEntity.ok().build();
    }

    @PostMapping(
            path = "/readings/motion",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE
    )
    public ResponseEntity<Void> receiveMotion(
            @Valid @ModelAttribute MotionReadingRequest request
    ) {
        motionService.ingest(
                request.sensorToken(),
                request.motionDetected()
        );

        return ResponseEntity.ok().build();
    }
}