package com.enginertugrul.iotsensormonitor.controller;

import com.enginertugrul.iotsensormonitor.dto.reading.HumidityReadingRequest;
import com.enginertugrul.iotsensormonitor.dto.reading.MotionReadingRequest;
import com.enginertugrul.iotsensormonitor.dto.reading.TemperatureReadingRequest;
import com.enginertugrul.iotsensormonitor.service.reading.ingestion.HumidityReadingIngestionService;
import com.enginertugrul.iotsensormonitor.service.reading.ingestion.MotionReadingIngestionService;
import com.enginertugrul.iotsensormonitor.service.reading.ingestion.TemperatureReadingIngestionService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
public class SensorIngestionController {



    private final TemperatureReadingIngestionService temperatureService;
    private final HumidityReadingIngestionService humidityService;
    private final MotionReadingIngestionService motionService;



    public SensorIngestionController(TemperatureReadingIngestionService temperatureService, HumidityReadingIngestionService humidityService, MotionReadingIngestionService motionService) {
        this.temperatureService = temperatureService;
        this.humidityService = humidityService;
        this.motionService = motionService;
    }






    @PostMapping(path = {"/readings/temperature"}, consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> receiveTemperature(
            @Valid @ModelAttribute TemperatureReadingRequest request) {

        temperatureService.ingest(request.sensorToken(), request.celsiusValue(), request.recordedAt());

        return ResponseEntity.ok().build();
    }





    @PostMapping(path = "/readings/humidity", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> receiveHumidity(
            @Valid @ModelAttribute HumidityReadingRequest request) {

        humidityService.ingest(request.sensorToken(),request.humidityPercentage(), request.recordedAt());

        return ResponseEntity.ok().build();
    }





    @PostMapping(path = "/readings/motion", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> receiveMotion(
            @Valid @ModelAttribute MotionReadingRequest request
    ) {

        motionService.ingest(request.sensorToken(), request.motionDetected(), request.recordedAt());

        return ResponseEntity.ok().build();
    }



}