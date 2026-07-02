package com.enginertugrul.iottemperaturemonitor.controller;

import com.enginertugrul.iottemperaturemonitor.dto.reading.SensorHourlyAverageDTO;
import com.enginertugrul.iottemperaturemonitor.dto.reading.SensorViewDTO;
import com.enginertugrul.iottemperaturemonitor.dto.sensor.SensorListItemDTO;
import com.enginertugrul.iottemperaturemonitor.security.AuthenticatedUser;
import com.enginertugrul.iottemperaturemonitor.service.reading.SensorReadingService;
import com.enginertugrul.iottemperaturemonitor.service.sensor.SensorService;
import org.slf4j.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.util.*;

@Controller
public class SensorReadingController {

    private final Logger logger = LoggerFactory.getLogger(SensorReadingController.class);
    private final SensorReadingService sensorReadingService;
    private final SensorService sensorService;

    public SensorReadingController(SensorReadingService sensorReadingService, SensorService sensorService) {
        this.sensorReadingService = sensorReadingService;
        this.sensorService = sensorService;
    }

    @GetMapping("/")
    public String getHomePage(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @RequestParam(value = "sensorId", required = false) Long sensorId,
            Model model
    ) {

        Long ownerId = authenticatedUser.getAppUserId();

        List<SensorViewDTO> recentRecords = List.of();
        Long selectedSensorId = null;

        if(sensorId != null) {
            try {
                recentRecords = sensorReadingService.getRecentTenRecords(sensorId, ownerId);
                selectedSensorId = sensorId;
            } catch (NoSuchElementException ex) {
                model.addAttribute("dashboardNotice", "No sensor selected");
            }
        }

        model.addAttribute("sensors", sensorService.getSensorsForUser(ownerId));
        model.addAttribute("selectedSensorId", selectedSensorId);
        model.addAttribute("recentRecords", recentRecords);
        return "index";
    }




    @PostMapping("/readings")
    public ResponseEntity<Void> receiveTemperatureData(
            @RequestParam("sensorToken") String sensorToken,
            @RequestParam("celsiusValue") Double celsiusValue
    ) {
        logger.info("Received temperature reading from token-authenticated sensor. value={}", celsiusValue);

        sensorReadingService.saveTemperatureReading(sensorToken, celsiusValue);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/statistics")
    public String getSensorStatistics(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @RequestParam(value = "sensorId", required = false) Long sensorId,
            Model model
    ) {
        Long ownerId = authenticatedUser.getAppUserId();
        List<SensorListItemDTO> sensors = sensorService.getSensorsForUser(ownerId);
        model.addAttribute("sensors", sensors);

        if (sensorId == null) {
            addEmptyStatisticsModel(model, "No sensor selected");
            return "statistics";
        }

        try {
            LocalDate today = sensorReadingService.getTodayForSensor(sensorId, ownerId);

            model.addAttribute("weeklyData", sensorReadingService.getDailyAverageFromLastWeek(sensorId, ownerId));
            model.addAttribute("hourlyData", sensorReadingService.getHourlyAverageForDate(sensorId, ownerId, today));
            model.addAttribute("today", today.toString());
            model.addAttribute("selectedSensorId", sensorId);
            model.addAttribute("selectedSensorName", getSensorName(sensors, sensorId));

        }catch (NoSuchElementException ex) {
            addEmptyStatisticsModel(model, "No sensor selected");
        }

        return "statistics";
    }

    @GetMapping("/api/statistics/hourly")
    @ResponseBody
    public ResponseEntity<List<SensorHourlyAverageDTO>> getHourlyDataForDate(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @RequestParam(value = "sensorId", required = false) Long sensorId,
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        if (sensorId == null) {
            return ResponseEntity.ok(emptyHourlyData());
        }
        try {
            return ResponseEntity.ok(
                    sensorReadingService.getHourlyAverageForDate(sensorId, authenticatedUser.getAppUserId(), date)
            );

        }catch (NoSuchElementException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    private List<SensorHourlyAverageDTO> emptyHourlyData() {
        List<SensorHourlyAverageDTO> result = new ArrayList<>();

        for (short hour = 0; hour <= 23; hour++) {
            result.add(new SensorHourlyAverageDTO(hour, null));
        }

        return result;
    }


    private void addEmptyStatisticsModel(Model model, String notice) {
        model.addAttribute("weeklyData", List.of());
        model.addAttribute("hourlyData", emptyHourlyData());
        model.addAttribute("today", LocalDate.now(ZoneOffset.UTC).toString());
        model.addAttribute("selectedSensorId", null);
        model.addAttribute("selectedSensorName", null);
        model.addAttribute("statisticsNotice", notice);
    }

    private String getSensorName(List<SensorListItemDTO> sensors, Long sensorId) {
        return sensors.stream()
                .filter(sensor -> sensor.id().equals(sensorId))
                .map(SensorListItemDTO::name)
                .findFirst()
                .orElse(null);
    }



}
