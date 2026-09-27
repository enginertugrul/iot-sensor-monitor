package com.enginertugrul.iotsensormonitor.service.reading;

import com.enginertugrul.iotsensormonitor.dto.reading.RecentSensorReadingsDTO;
import com.enginertugrul.iotsensormonitor.dto.reading.SensorReadingSnapshotDTO;
import com.enginertugrul.iotsensormonitor.dto.reading.SensorReadingViewDTO;
import com.enginertugrul.iotsensormonitor.entity.measurement.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.reading.SensorReading;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.entity.user.PreferredLanguage;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.exception.SensorNotFoundException;
import com.enginertugrul.iotsensormonitor.repository.AppUserRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorReadingRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorRepository;
import com.enginertugrul.iotsensormonitor.testsupport.PostgresTestConfiguration;
import com.enginertugrul.iotsensormonitor.testsupport.TestRuntimeConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.enginertugrul.iotsensormonitor.testsupport.TestRuntimeConfiguration.TEST_INSTANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;



@SpringBootTest(properties = "spring.config.location=classpath:/application-test.properties")
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class,TestRuntimeConfiguration.class})
class SensorReadingServiceIT {

    private static final Instant SENSOR_CREATED_AT = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant RECORDED_AT = TEST_INSTANT.minusSeconds(3600);

    @Autowired
    private SensorReadingService service;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private SensorRepository sensorRepository;

    @Autowired
    private SensorReadingRepository readingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> fixtureOwnerIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (Long ownerId : fixtureOwnerIds) {
            jdbcTemplate.update("DELETE FROM sensors WHERE owner_id=?",ownerId);
            jdbcTemplate.update("DELETE FROM app_users WHERE id=?",ownerId);
        }
    }



    @Test
    void returnsLatestTenViewsAndSnapshotsWithDescendingIdOrderForTimestampTies() {
        AppUser owner = persistUser();
        AppUser otherOwner = persistUser();
        Sensor target = persistSensor(owner,SensorType.TEMPERATURE,"Target","UTC");
        Sensor sibling = persistSensor(owner,SensorType.TEMPERATURE,"Sibling","UTC");
        Sensor foreign = persistSensor(otherOwner,SensorType.TEMPERATURE,"Foreign","UTC");

        for (int index = 0; index < 11; index++) {
            persistReading(target,20.0 + index,null,RECORDED_AT);
        }

        persistReading(target,99.0,null,RECORDED_AT.plusSeconds(1));
        persistReading(target,-10.0,null,RECORDED_AT.minusSeconds(1));
        persistReading(sibling,200.0,null,RECORDED_AT.plusSeconds(10));
        persistReading(foreign,300.0,null,RECORDED_AT.plusSeconds(20));

        List<Double> expectedValues = List.of(99.0,30.0,29.0,28.0,27.0,26.0,25.0,24.0,23.0,22.0);

        List<SensorReadingViewDTO> views = service.getRecentReadings(target.getId(),owner.getId(),TemperatureUnit.CELSIUS);
        RecentSensorReadingsDTO snapshot = service.getRecentReadingsSnapshot(target.getId(),owner.getId(),TemperatureUnit.CELSIUS);

        assertThat(views).hasSize(10)
                .extracting(SensorReadingViewDTO::numericValue).containsExactlyElementsOf(expectedValues);
        assertThat(views.getFirst().timestamp().toInstant()).isEqualTo(RECORDED_AT.plusSeconds(1));
        assertThat(views.subList(1,10)).allSatisfy(view -> assertThat(view.timestamp().toInstant()).isEqualTo(RECORDED_AT));

        assertThat(snapshot.sensorId()).isEqualTo(target.getId());
        assertThat(snapshot.readings()).hasSize(10)
                .extracting(SensorReadingSnapshotDTO::numericValue).containsExactlyElementsOf(expectedValues);
        assertThat(snapshot.readings().getFirst().timestamp()).isEqualTo(RECORDED_AT.plusSeconds(1).toString());
        assertThat(snapshot.readings().subList(1,10))
                .allSatisfy(reading -> assertThat(reading.timestamp()).isEqualTo(RECORDED_AT.toString()));
    }



    @ParameterizedTest
    @EnumSource(SensorType.class)
    void returnsEmptyViewsAndSnapshotForAnOwnedSensorWithoutReadings(SensorType type) {
        AppUser owner = persistUser();
        Sensor sensor = persistSensor(owner,type,"Empty","UTC");

        assertThat(service.getRecentReadings(sensor.getId(),owner.getId(),TemperatureUnit.CELSIUS)).isEmpty();
        assertThat(service.getRecentReadingsSnapshot(sensor.getId(),owner.getId(),TemperatureUnit.CELSIUS))
                .isEqualTo(new RecentSensorReadingsDTO(sensor.getId(),List.of()));
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void treatsMissingAndForeignSensorsIdentically(boolean snapshot) {
        AppUser owner = persistUser();
        AppUser otherOwner = persistUser();
        Sensor foreign = persistSensor(otherOwner,SensorType.TEMPERATURE,"Foreign","UTC");
        persistReading(foreign,20.0,null,RECORDED_AT);

        assertThatThrownBy(() -> requestRead(snapshot,-1L,owner.getId()))
                .isExactlyInstanceOf(SensorNotFoundException.class)
                .hasMessage("Sensor not found")
                .hasNoCause();

        assertThatThrownBy(() -> requestRead(snapshot,foreign.getId(),owner.getId()))
                .isExactlyInstanceOf(SensorNotFoundException.class)
                .hasMessage("Sensor not found")
                .hasNoCause();

        assertThat(service.getRecentReadings(foreign.getId(),otherOwner.getId(),TemperatureUnit.CELSIUS)).hasSize(1);
    }



    @ParameterizedTest
    @CsvSource(value = {
            "CELSIUS,20.0,°C",
            "FAHRENHEIT,68.0,°F",
            "KELVIN,293.15,K",
            "NULL,20.0,°C"
    },nullValues = "NULL")
    void appliesRequestedTemperatureUnitWithoutChangingStoredCelsiusValue(TemperatureUnit unit,double expectedValue,String expectedSymbol) {
        AppUser owner = persistUser();
        Sensor sensor = persistSensor(owner,SensorType.TEMPERATURE,"Temperature","Europe/Istanbul");
        SensorReading stored = persistReading(sensor,20.0,null,RECORDED_AT);

        List<SensorReadingViewDTO> views = service.getRecentReadings(sensor.getId(),owner.getId(),unit);
        RecentSensorReadingsDTO snapshot = service.getRecentReadingsSnapshot(sensor.getId(),owner.getId(),unit);

        assertThat(views).hasSize(1);
        SensorReadingViewDTO view = views.getFirst();
        assertThat(view.sensorType()).isEqualTo(SensorType.TEMPERATURE);
        assertThat(view.installationLocation()).isEqualTo("Window");
        assertThat(view.numericValue()).isCloseTo(expectedValue,within(1.0e-9));
        assertThat(view.booleanValue()).isNull();
        assertThat(view.unitSymbol()).isEqualTo(expectedSymbol);
        assertThat(view.timestamp().getZone()).isEqualTo(ZoneId.of("Europe/Istanbul"));
        assertThat(view.timestamp().getOffset().getId()).isEqualTo("+03:00");
        assertThat(view.timestamp().toInstant()).isEqualTo(RECORDED_AT);

        assertThat(snapshot.sensorId()).isEqualTo(sensor.getId());
        assertThat(snapshot.readings()).hasSize(1);
        SensorReadingSnapshotDTO item = snapshot.readings().getFirst();
        assertThat(item.numericValue()).isCloseTo(expectedValue,within(1.0e-9));
        assertThat(item.unitSymbol()).isEqualTo(expectedSymbol);
        assertThat(item.timestamp()).isEqualTo("2026-01-15T14:00:00+03:00");
        assertThat(item.timeZoneId()).isEqualTo("Europe/Istanbul");
        assertThat(item.offset()).isEqualTo("+03:00");

        SensorReading reloaded = readingRepository.findById(stored.getId()).orElseThrow();
        assertThat(reloaded.getNumericValue()).isEqualTo(20.0);
        assertThat(reloaded.getUnit()).isEqualTo(MeasurementUnit.C);
        assertThat(reloaded.getRecordedAt()).isEqualTo(RECORDED_AT);
    }



    @ParameterizedTest
    @CsvSource({
            "HUMIDITY,45.5,,% RH",
            "MOTION,,true,''",
            "MOTION,,false,''"
    })
    void mapsPersistedHumidityAndMotionWithoutTemperatureConversion(SensorType type,Double numericValue,Boolean booleanValue,String unitSymbol) {
        AppUser owner = persistUser();
        Sensor sensor = persistSensor(owner,type,"Sensor","UTC");
        persistReading(sensor,numericValue,booleanValue,RECORDED_AT);

        List<SensorReadingViewDTO> views = service.getRecentReadings(sensor.getId(),owner.getId(),TemperatureUnit.FAHRENHEIT);
        RecentSensorReadingsDTO snapshot = service.getRecentReadingsSnapshot(sensor.getId(),owner.getId(),TemperatureUnit.FAHRENHEIT);

        SensorReadingViewDTO expectedView = new SensorReadingViewDTO(type,"Window",numericValue,booleanValue,unitSymbol,RECORDED_AT.atZone(ZoneId.of("UTC")));
        SensorReadingSnapshotDTO expectedSnapshot = new SensorReadingSnapshotDTO(type,"Window",numericValue,booleanValue,unitSymbol,RECORDED_AT.toString(),"UTC","Z");
        assertThat(views).containsExactly(expectedView);
        assertThat(snapshot.sensorId()).isEqualTo(sensor.getId());
        assertThat(snapshot.readings()).containsExactly(expectedSnapshot);
    }



    @Test
    void distinguishesRepeatedLocalTimesByTheirOffsetsAndPreservesMicroseconds() {
        AppUser owner = persistUser();
        Sensor sensor = persistSensor(owner,SensorType.TEMPERATURE,"DST sensor","America/New_York");
        Instant earlier = Instant.parse("2025-11-02T05:30:00.123456Z");
        Instant later = Instant.parse("2025-11-02T06:30:00.123456Z");
        persistReading(sensor,22.0,null,later);
        persistReading(sensor,21.0,null,earlier);

        List<SensorReadingViewDTO> views = service.getRecentReadings(sensor.getId(),owner.getId(),TemperatureUnit.CELSIUS);
        RecentSensorReadingsDTO snapshot = service.getRecentReadingsSnapshot(sensor.getId(),owner.getId(),TemperatureUnit.CELSIUS);

        assertThat(views).hasSize(2)
                .extracting(view -> view.timestamp().toInstant()).containsExactly(later,earlier);
        assertThat(views.get(0).timestamp().toLocalDateTime()).isEqualTo(views.get(1).timestamp().toLocalDateTime());
        assertThat(views).extracting(view -> view.timestamp().getOffset().getId()).containsExactly("-05:00","-04:00");
        assertThat(views).allSatisfy(view -> assertThat(view.timestamp().getZone()).isEqualTo(ZoneId.of("America/New_York")));

        SensorReadingSnapshotDTO expectedLater = new SensorReadingSnapshotDTO(SensorType.TEMPERATURE,"Window",22.0,null,"°C","2025-11-02T01:30:00.123456-05:00","America/New_York","-05:00");
        SensorReadingSnapshotDTO expectedEarlier = new SensorReadingSnapshotDTO(SensorType.TEMPERATURE,"Window",21.0,null,"°C","2025-11-02T01:30:00.123456-04:00","America/New_York","-04:00");
        assertThat(snapshot.readings()).containsExactly(expectedLater,expectedEarlier);
    }



    @Test
    void producesEqualSnapshotsUntilReadingsOrDisplayedSensorDetailsChange() {
        AppUser owner = persistUser();
        Sensor sensor = persistSensor(owner,SensorType.TEMPERATURE,"Temperature","UTC");
        persistReading(sensor,20.0,null,RECORDED_AT);

        RecentSensorReadingsDTO original = service.getRecentReadingsSnapshot(sensor.getId(),owner.getId(),TemperatureUnit.CELSIUS);
        RecentSensorReadingsDTO repeated = service.getRecentReadingsSnapshot(sensor.getId(),owner.getId(),TemperatureUnit.CELSIUS);
        assertThat(repeated).isEqualTo(original);

        persistReading(sensor,21.0,null,RECORDED_AT.plusSeconds(60));

        RecentSensorReadingsDTO afterReading = service.getRecentReadingsSnapshot(sensor.getId(),owner.getId(),TemperatureUnit.CELSIUS);
        assertThat(afterReading).isNotEqualTo(original);
        assertThat(afterReading.readings()).extracting(SensorReadingSnapshotDTO::numericValue).containsExactly(21.0,20.0);
        assertThat(original.readings()).extracting(SensorReadingSnapshotDTO::numericValue).containsExactly(20.0);

        sensor.updateDetails("Temperature","Istanbul","Kadikoy","Door","UTC",TEST_INSTANT);
        sensorRepository.saveAndFlush(sensor);

        RecentSensorReadingsDTO afterLocationChange = service.getRecentReadingsSnapshot(sensor.getId(),owner.getId(),TemperatureUnit.CELSIUS);
        assertThat(afterLocationChange).isNotEqualTo(afterReading);
        assertThat(afterLocationChange.readings()).hasSize(2)
                .allSatisfy(reading -> assertThat(reading.installationLocation()).isEqualTo("Door"));
        assertThat(afterReading.readings()).allSatisfy(reading -> assertThat(reading.installationLocation()).isEqualTo("Window"));
    }



    @Test
    void keepsHistoricalReadingsVisibleWhenAnOwnedSensorIsInactive() {
        AppUser owner = persistUser();
        Sensor sensor = persistSensor(owner,SensorType.TEMPERATURE,"Inactive","UTC");
        persistReading(sensor,20.0,null,RECORDED_AT);
        sensor.deactivate(TEST_INSTANT);
        sensorRepository.saveAndFlush(sensor);

        assertThat(service.getRecentReadings(sensor.getId(),owner.getId(),TemperatureUnit.CELSIUS))
                .extracting(SensorReadingViewDTO::numericValue).containsExactly(20.0);
        assertThat(service.getRecentReadingsSnapshot(sensor.getId(),owner.getId(),TemperatureUnit.CELSIUS).readings())
                .extracting(SensorReadingSnapshotDTO::numericValue).containsExactly(20.0);
    }



    private AppUser persistUser() {
        AppUser user = new AppUser("recent-readings-" + UUID.randomUUID() + "@example.com","test-password-hash",PreferredLanguage.ENGLISH,TemperatureUnit.KELVIN,"Asia/Tokyo",SENSOR_CREATED_AT);
        AppUser saved = appUserRepository.saveAndFlush(user);
        fixtureOwnerIds.add(saved.getId());
        return saved;
    }



    private Sensor persistSensor(AppUser owner,SensorType type,String name,String timezone) {
        return sensorRepository.saveAndFlush(new Sensor(owner,type,name,"Istanbul","Kadikoy","Window",timezone,SENSOR_CREATED_AT));
    }



    private SensorReading persistReading(Sensor sensor,Double numericValue,Boolean booleanValue,Instant recordedAt) {
        SensorReading reading = switch (sensor.getType()) {
            case TEMPERATURE -> SensorReading.temperature(sensor,numericValue,recordedAt);
            case HUMIDITY -> SensorReading.humidity(sensor,numericValue,recordedAt);
            case MOTION -> SensorReading.motion(sensor,booleanValue,recordedAt);
        };
        return readingRepository.saveAndFlush(reading);
    }



    private void requestRead(boolean snapshot,Long sensorId,Long ownerId) {
        if (snapshot) {
            service.getRecentReadingsSnapshot(sensorId,ownerId,TemperatureUnit.CELSIUS);
        } else {
            service.getRecentReadings(sensorId,ownerId,TemperatureUnit.CELSIUS);
        }
    }
}