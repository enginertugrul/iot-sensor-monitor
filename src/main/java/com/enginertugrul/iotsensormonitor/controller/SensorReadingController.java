package com.enginertugrul.iotsensormonitor.controller;

import com.enginertugrul.iotsensormonitor.dto.reading.SensorReadingViewDTO;
import com.enginertugrul.iotsensormonitor.dto.sensor.SensorListItemDTO;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.security.AuthenticatedUser;
import com.enginertugrul.iotsensormonitor.service.reading.SensorReadingService;
import com.enginertugrul.iotsensormonitor.service.sensor.SensorService;
import com.enginertugrul.iotsensormonitor.service.user.AppUserService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

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
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @RequestParam(value = "sensorId",required = false) Long sensorId,
            Model model) {

        Long ownerId = authenticatedUser.getAppUserId();
        List<SensorListItemDTO> sensors = sensorService.getSensorsForUser(ownerId);
        SensorListItemDTO selectedSensor = findSensor(sensors,sensorId);
        List<SensorReadingViewDTO> recentRecords = List.of();

        if (selectedSensor != null) {
            TemperatureUnit temperatureUnit = appUserService.getPreferredTemperatureUnit(ownerId);

            try {
                recentRecords = sensorReadingService.getRecentReadings(
                        selectedSensor.id(),
                        ownerId,
                        temperatureUnit);
            } catch (NoSuchElementException exception) {
                selectedSensor = null;
            }
        }

        model.addAttribute("sensors",sensors);
        model.addAttribute("selectedSensorId", selectedSensor == null ? null : selectedSensor.id());
        model.addAttribute("selectedSensorType", selectedSensor == null ? null : selectedSensor.type());
        model.addAttribute("recentRecords",recentRecords);

        return "index";
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
}