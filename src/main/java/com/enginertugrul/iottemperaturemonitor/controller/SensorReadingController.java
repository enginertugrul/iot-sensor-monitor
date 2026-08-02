package com.enginertugrul.iottemperaturemonitor.controller;

import com.enginertugrul.iottemperaturemonitor.dto.reading.SensorHourlyStatisticDTO;
import com.enginertugrul.iottemperaturemonitor.dto.reading.SensorReadingViewDTO;
import com.enginertugrul.iottemperaturemonitor.dto.reading.SensorStatisticsDTO;
import com.enginertugrul.iottemperaturemonitor.dto.sensor.SensorListItemDTO;
import com.enginertugrul.iottemperaturemonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iottemperaturemonitor.security.AuthenticatedUser;
import com.enginertugrul.iottemperaturemonitor.service.reading.SensorReadingService;
import com.enginertugrul.iottemperaturemonitor.service.sensor.SensorService;
import com.enginertugrul.iottemperaturemonitor.service.user.AppUserService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;





@Controller
public class SensorReadingController {



    private final SensorReadingService sensorReadingService;
    private final SensorService sensorService;
    private final AppUserService appUserService;




    public SensorReadingController(SensorReadingService sensorReadingService, SensorService sensorService, AppUserService appUserService) {
        this.sensorReadingService = sensorReadingService;
        this.sensorService = sensorService;
        this.appUserService = appUserService;
    }








    @GetMapping("/")
    public String getHomePage(
            @AuthenticationPrincipal
            AuthenticatedUser authenticatedUser,
            @RequestParam(value = "sensorId", required = false)
            Long sensorId,
            Model model) {

        Long ownerId = authenticatedUser.getAppUserId();

        List<SensorListItemDTO> sensors = sensorService.getSensorsForUser(ownerId);

        SensorListItemDTO selectedSensor = findSensor(sensors, sensorId);

        List<SensorReadingViewDTO> recentRecords = List.of();


        if (selectedSensor != null) {

            TemperatureUnit temperatureUnit =appUserService.getPreferredTemperatureUnit(ownerId);

            try {
                recentRecords = sensorReadingService.getRecentReadings(selectedSensor.id(), ownerId, temperatureUnit);
            } catch (NoSuchElementException exception) {
                selectedSensor = null;
            }

        }

        model.addAttribute("sensors", sensors);

        model.addAttribute("selectedSensorId",
                selectedSensor == null
                        ? null
                        : selectedSensor.id());

        model.addAttribute("selectedSensorType",
                selectedSensor == null
                        ? null
                        : selectedSensor.type());

        model.addAttribute("recentRecords",recentRecords);

        return "index";
    }







    @GetMapping("/statistics")
    public String getSensorStatistics(
            @AuthenticationPrincipal
            AuthenticatedUser authenticatedUser,
            @RequestParam(value = "sensorId",required = false)
            Long sensorId,
            Model model) {

        Long ownerId = authenticatedUser.getAppUserId();

        List<SensorListItemDTO> sensors = sensorService.getSensorsForUser(ownerId);

        model.addAttribute("sensors", sensors);

        if (sensorId == null) {
            addEmptyStatisticsModel(model, false);
            return "statistics";
        }

        SensorListItemDTO selectedSensor = findSensor(sensors, sensorId);

        if (selectedSensor == null) {
            addEmptyStatisticsModel(model, true);
            return "statistics";
        }

        TemperatureUnit temperatureUnit = appUserService.getPreferredTemperatureUnit(ownerId);

        try {
            SensorStatisticsDTO statistics = sensorReadingService.getStatistics(selectedSensor.id(), ownerId, temperatureUnit);
            addStatisticsModel(model, selectedSensor, statistics);
        } catch (NoSuchElementException exception) {
            addEmptyStatisticsModel(model, true);
        }

        return "statistics";
    }







    @GetMapping("/api/sensors/{sensorId}/statistics/hourly")
    @ResponseBody
    public ResponseEntity<List<SensorHourlyStatisticDTO>> getHourlyStatistics(
            @AuthenticationPrincipal
            AuthenticatedUser authenticatedUser,
            @PathVariable Long sensorId,
            @RequestParam("date")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date) {

        Long ownerId = authenticatedUser.getAppUserId();

        TemperatureUnit temperatureUnit = appUserService.getPreferredTemperatureUnit(ownerId);

        try {
            return ResponseEntity.ok(sensorReadingService.getHourlyStatisticsForDate(
                            sensorId,
                            ownerId,
                            date,
                            temperatureUnit));

        } catch (NoSuchElementException exception) {
            return ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .build();
        }
    }






    private SensorListItemDTO findSensor(List<SensorListItemDTO> sensors,Long sensorId) {

        if (sensorId == null) {
            return null;
        }

        return sensors.stream()
                .filter(sensor -> sensor.id().equals(sensorId))
                .findFirst()
                .orElse(null);
    }





    private void addStatisticsModel(Model model, SensorListItemDTO selectedSensor,SensorStatisticsDTO statistics) {

        model.addAttribute("selectedSensorId", selectedSensor.id());
        model.addAttribute("selectedSensorName",selectedSensor.name());
        model.addAttribute("selectedSensorType",statistics.sensorType());
        model.addAttribute("measurementUnitSymbol",statistics.measurementUnitSymbol());
        model.addAttribute("today",statistics.today().toString());
        model.addAttribute("weeklyData",statistics.weeklyData());
        model.addAttribute("hourlyData",statistics.hourlyData());

        model.addAttribute("statisticsNoSensorSelected",false);
        model.addAttribute("statisticsSensorNotFound",false);

    }



    private void addEmptyStatisticsModel(Model model, boolean sensorNotFound) {

        model.addAttribute("selectedSensorId", null);
        model.addAttribute("selectedSensorName", null);
        model.addAttribute("selectedSensorType", null);
        model.addAttribute("measurementUnitSymbol", "");
        model.addAttribute("today", null);
        model.addAttribute("weeklyData", List.of());
        model.addAttribute("hourlyData", List.of());

        model.addAttribute("statisticsNoSensorSelected",!sensorNotFound);
        model.addAttribute("statisticsSensorNotFound",sensorNotFound);
    }



}