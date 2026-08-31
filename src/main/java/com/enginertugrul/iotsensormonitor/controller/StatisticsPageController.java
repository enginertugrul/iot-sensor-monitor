package com.enginertugrul.iotsensormonitor.controller;

import com.enginertugrul.iotsensormonitor.dto.sensor.SensorListItemDTO;
import com.enginertugrul.iotsensormonitor.dto.statistics.SensorStatisticsSeriesDTO;
import com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsPageQuery;
import com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsRangePreset;
import com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsResolution;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.exception.InvalidStatisticsQueryException;
import com.enginertugrul.iotsensormonitor.exception.SensorNotFoundException;
import com.enginertugrul.iotsensormonitor.security.AuthenticatedUser;
import com.enginertugrul.iotsensormonitor.service.reading.statistics.StatisticsQueryService;
import com.enginertugrul.iotsensormonitor.service.sensor.SensorService;
import com.enginertugrul.iotsensormonitor.service.user.AppUserService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;





@Controller
public class StatisticsPageController {


    private static final List<StatisticsRangePreset> SELECTABLE_PRESETS = List.of(
            StatisticsRangePreset.LAST_HOUR,
            StatisticsRangePreset.TODAY,
            StatisticsRangePreset.LAST_7_DAYS,
            StatisticsRangePreset.LAST_30_DAYS,
            StatisticsRangePreset.LAST_90_DAYS,
            StatisticsRangePreset.ONE_YEAR,
            StatisticsRangePreset.CUSTOM);


    private final SensorService sensorService;
    private final StatisticsQueryService statisticsQueryService;
    private final AppUserService appUserService;



    public StatisticsPageController(SensorService sensorService,StatisticsQueryService statisticsQueryService,AppUserService appUserService) {
        this.sensorService = sensorService;
        this.statisticsQueryService = statisticsQueryService;
        this.appUserService = appUserService;
    }




    @GetMapping("/statistics")
    public String getStatisticsPage(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @ModelAttribute("statisticsQuery") StatisticsPageQuery statisticsQuery,
            BindingResult bindingResult,
            Model model) {

        Long ownerId = authenticatedUser.getAppUserId();
        List<SensorListItemDTO> sensors = sensorService.getSensorsForUser(ownerId);

        initializeModel(model,sensors);

        if (bindingResult.hasFieldErrors("sensorId")) {
            model.addAttribute("statisticsQueryInvalid",true);
            return "statistics";
        }

        if (statisticsQuery.getSensorId() == null) {
            if (bindingResult.hasErrors()) {
                model.addAttribute("statisticsQueryInvalid",true);
            } else {
                model.addAttribute("statisticsNoSensorSelected",true);
            }

            return "statistics";
        }

        SensorListItemDTO selectedSensor = findSensor(sensors,statisticsQuery.getSensorId());

        if (selectedSensor == null) {
            markSensorNotFound(model);
            return "statistics";
        }

        addSelectedSensorModel(model,selectedSensor);

        ZoneId sensorTimeZone = ZoneId.of(selectedSensor.timezone());
        Instant pageNow = Instant.now();
        LocalDate sensorToday = pageNow.atZone(sensorTimeZone).toLocalDate();

        model.addAttribute("statisticsToday",sensorToday);

        if (bindingResult.hasErrors()) {
            model.addAttribute("statisticsQueryInvalid",true);
            return "statistics";
        }

        StatisticsResolution requestedResolution = statisticsQuery.getResolution() == null
                ? StatisticsResolution.AUTO
                : statisticsQuery.getResolution();

        StatisticsRangePreset selectedPreset = defaultPreset(statisticsQuery.getPreset());
        ResolvedStatisticsRange resolvedRange;

        try {
            resolvedRange = resolveRange(statisticsQuery,selectedPreset,sensorTimeZone,sensorToday,pageNow);
        } catch (InvalidStatisticsQueryException | DateTimeException | ArithmeticException exception) {
            model.addAttribute("statisticsQueryInvalid",true);
            return "statistics";
        }

        statisticsQuery.setPreset(selectedPreset);
        statisticsQuery.setResolution(requestedResolution);
        statisticsQuery.setStartDate(resolvedRange.startDate());
        statisticsQuery.setEndDate(resolvedRange.endDate());

        TemperatureUnit temperatureUnit = appUserService.getPreferredTemperatureUnit(ownerId);
        SensorStatisticsSeriesDTO series;

        try {
            series = statisticsQueryService.getSeries(selectedSensor.id(),ownerId,resolvedRange.startInclusive(),resolvedRange.endExclusive(),requestedResolution,temperatureUnit);
        } catch (SensorNotFoundException exception) {
            markSensorNotFound(model);
            return "statistics";
        } catch (InvalidStatisticsQueryException exception) {
            model.addAttribute("statisticsQueryInvalid",true);
            return "statistics";
        }

        model.addAttribute("statisticsSeries",series);
        model.addAttribute("statisticsCoverageTiers",List.of(series.coverage().raw(),series.coverage().hourly(),series.coverage().daily()));

        return "statistics";
    }

    private ResolvedStatisticsRange resolveRange(
            StatisticsPageQuery query,
            StatisticsRangePreset preset,
            ZoneId sensorTimeZone,
            LocalDate sensorToday,
            Instant pageNow) {

        return switch (preset) {
            case LAST_HOUR -> {
                Instant startInclusive = pageNow.minus(1,ChronoUnit.HOURS);
                LocalDate startDate = startInclusive.atZone(sensorTimeZone).toLocalDate();
                yield new ResolvedStatisticsRange(startInclusive,pageNow,startDate,sensorToday);
            }
            case TODAY -> calendarRange(sensorToday,sensorToday,sensorTimeZone);
            case LAST_7_DAYS -> calendarRange(sensorToday.minusDays(6),sensorToday,sensorTimeZone);
            case LAST_30_DAYS -> calendarRange(sensorToday.minusDays(29),sensorToday,sensorTimeZone);
            case LAST_90_DAYS -> calendarRange(sensorToday.minusDays(89),sensorToday,sensorTimeZone);
            case ONE_YEAR -> calendarRange(sensorToday.minusYears(1).plusDays(1),sensorToday,sensorTimeZone);
            case CUSTOM -> calendarRange(query.getStartDate(),query.getEndDate(),sensorTimeZone);
            case EXACT -> exactRange(query,sensorTimeZone);
        };
    }

    private ResolvedStatisticsRange exactRange(StatisticsPageQuery query,ZoneId sensorTimeZone) {
        Instant startInclusive = query.getStartInclusive();
        Instant endExclusive = query.getEndExclusive();

        if (startInclusive == null || endExclusive == null) {
            throw new InvalidStatisticsQueryException("startInclusive and endExclusive are required for an exact range");
        }

        if (!startInclusive.isBefore(endExclusive)) {
            throw new InvalidStatisticsQueryException("startInclusive must be before endExclusive");
        }

        LocalDate startDate = startInclusive.atZone(sensorTimeZone).toLocalDate();
        LocalDate endDate = endExclusive.minusNanos(1).atZone(sensorTimeZone).toLocalDate();

        return new ResolvedStatisticsRange(startInclusive,endExclusive,startDate,endDate);
    }

    private ResolvedStatisticsRange calendarRange(LocalDate startDate,LocalDate endDate,ZoneId sensorTimeZone) {
        if (startDate == null || endDate == null) {
            throw new InvalidStatisticsQueryException("startDate and endDate are required for a calendar range");
        }

        if (startDate.isAfter(endDate)) {
            throw new InvalidStatisticsQueryException("startDate must not be after endDate");
        }

        Instant startInclusive = startDate.atStartOfDay(sensorTimeZone).toInstant();
        Instant endExclusive = endDate.plusDays(1).atStartOfDay(sensorTimeZone).toInstant();

        if (!startInclusive.isBefore(endExclusive)) {
            throw new InvalidStatisticsQueryException("The selected local-date range does not produce a valid instant range");
        }

        return new ResolvedStatisticsRange(startInclusive,endExclusive,startDate,endDate);
    }

    private StatisticsRangePreset defaultPreset(StatisticsRangePreset preset) {
        return preset == null ? StatisticsRangePreset.LAST_7_DAYS : preset;
    }

    private SensorListItemDTO findSensor(List<SensorListItemDTO> sensors,Long sensorId) {
        return sensors.stream()
                .filter(sensor -> sensor.id().equals(sensorId))
                .findFirst()
                .orElse(null);
    }

    private void initializeModel(Model model,List<SensorListItemDTO> sensors) {
        model.addAttribute("sensors",sensors);
        model.addAttribute("statisticsPresets",SELECTABLE_PRESETS);
        model.addAttribute("selectedSensor",null);
        model.addAttribute("selectedSensorId",null);
        model.addAttribute("statisticsToday",null);
        model.addAttribute("statisticsSeries",null);
        model.addAttribute("statisticsCoverageTiers",List.of());
        model.addAttribute("statisticsNoSensorSelected",false);
        model.addAttribute("statisticsSensorNotFound",false);
        model.addAttribute("statisticsQueryInvalid",false);
    }

    private void addSelectedSensorModel(Model model,SensorListItemDTO selectedSensor) {
        model.addAttribute("selectedSensor",selectedSensor);
        model.addAttribute("selectedSensorId",selectedSensor.id());
    }

    private void markSensorNotFound(Model model) {
        model.addAttribute("selectedSensor",null);
        model.addAttribute("selectedSensorId",null);
        model.addAttribute("statisticsSeries",null);
        model.addAttribute("statisticsCoverageTiers",List.of());
        model.addAttribute("statisticsNoSensorSelected",false);
        model.addAttribute("statisticsSensorNotFound",true);
        model.addAttribute("statisticsQueryInvalid",false);
    }

    private record ResolvedStatisticsRange(
            Instant startInclusive,
            Instant endExclusive,
            LocalDate startDate,
            LocalDate endDate
    ) {
    }
}