package com.enginertugrul.iotsensormonitor.service.reading;

import com.enginertugrul.iotsensormonitor.dto.reading.RecentSensorReadingsDTO;
import com.enginertugrul.iotsensormonitor.dto.reading.SensorReadingSnapshotDTO;
import com.enginertugrul.iotsensormonitor.dto.reading.SensorReadingViewDTO;
import com.enginertugrul.iotsensormonitor.entity.reading.SensorReading;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.exception.SensorNotFoundException;
import com.enginertugrul.iotsensormonitor.repository.SensorReadingRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorRepository;
import com.enginertugrul.iotsensormonitor.support.temperature.TemperatureUnitConverter;
import com.enginertugrul.iotsensormonitor.testsupport.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;



@ExtendWith(MockitoExtension.class)
class SensorReadingServiceImplTest {

    private static final Long SENSOR_ID = 100L;
    private static final Long OWNER_ID = 42L;
    private static final Instant RECORDED_AT = CREATED_AT.plusSeconds(3600);

    @Mock
    private SensorReadingRepository readingRepository;

    @Mock
    private SensorRepository sensorRepository;

    private SensorReadingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SensorReadingServiceImpl(readingRepository,sensorRepository,new TemperatureUnitConverter());
    }



    @ParameterizedTest
    @CsvSource(value = {
            "CELSIUS,20.0,°C",
            "FAHRENHEIT,68.0,°F",
            "KELVIN,293.15,K",
            "NULL,20.0,°C"
    },nullValues = "NULL")
    void mapsTemperatureUsingRequestedUnitAndSensorTimezone(TemperatureUnit unit,double expectedValue,String expectedSymbol) {
        Sensor sensor = givenSensor(SensorType.TEMPERATURE,"Europe/Istanbul");
        SensorReading reading = SensorReading.temperature(sensor,20.0,RECORDED_AT);
        givenReadings(reading);

        List<SensorReadingViewDTO> result = service.getRecentReadings(SENSOR_ID,OWNER_ID,unit);

        assertThat(result).hasSize(1);
        SensorReadingViewDTO view = result.getFirst();
        assertThat(view.sensorType()).isEqualTo(SensorType.TEMPERATURE);
        assertThat(view.installationLocation()).isEqualTo("Window");
        assertThat(view.numericValue()).isCloseTo(expectedValue,within(1.0e-9));
        assertThat(view.booleanValue()).isNull();
        assertThat(view.unitSymbol()).isEqualTo(expectedSymbol);
        assertThat(view.timestamp()).isEqualTo(ZonedDateTime.parse("2026-01-15T16:00:00+03:00[Europe/Istanbul]"));
        assertThat(reading.getNumericValue()).isEqualTo(20.0);
        assertThat(reading.getRecordedAt()).isEqualTo(RECORDED_AT);
    }



    @ParameterizedTest
    @EnumSource(TemperatureUnit.class)
    @NullSource
    void preservesHumidityValueAndUnitForEveryTemperaturePreference(TemperatureUnit unit) {
        Sensor sensor = givenSensor(SensorType.HUMIDITY,"UTC");
        givenReadings(SensorReading.humidity(sensor,45.5,RECORDED_AT));

        List<SensorReadingViewDTO> result = service.getRecentReadings(SENSOR_ID,OWNER_ID,unit);

        SensorReadingViewDTO expected = new SensorReadingViewDTO(SensorType.HUMIDITY,"Window",45.5,null,"% RH",ZonedDateTime.parse("2026-01-15T13:00:00Z[UTC]"));
        assertThat(result).containsExactly(expected);
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void preservesMotionStateWithoutNumericValueOrUnit(boolean motionDetected) {
        Sensor sensor = givenSensor(SensorType.MOTION,"UTC");
        givenReadings(SensorReading.motion(sensor,motionDetected,RECORDED_AT));

        List<SensorReadingViewDTO> result = service.getRecentReadings(SENSOR_ID,OWNER_ID,TemperatureUnit.FAHRENHEIT);

        SensorReadingViewDTO expected = new SensorReadingViewDTO(SensorType.MOTION,"Window",null,motionDetected,"",ZonedDateTime.parse("2026-01-15T13:00:00Z[UTC]"));
        assertThat(result).containsExactly(expected);
    }



    @Test
    void checksOwnershipBeforeLoadingReadingsAndPreservesRepositoryOrder() {
        Sensor sensor = givenSensor(SensorType.HUMIDITY,"UTC");
        SensorReading newest = SensorReading.humidity(sensor,60.0,RECORDED_AT.plusSeconds(1));
        SensorReading firstTie = SensorReading.humidity(sensor,55.0,RECORDED_AT);
        SensorReading secondTie = SensorReading.humidity(sensor,50.0,RECORDED_AT);
        givenReadings(newest,firstTie,secondTie);

        List<SensorReadingViewDTO> result = service.getRecentReadings(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS);

        assertThat(result).extracting(SensorReadingViewDTO::numericValue).containsExactly(60.0,55.0,50.0);
        assertThat(result).extracting(view -> view.timestamp().toInstant())
                .containsExactly(RECORDED_AT.plusSeconds(1),RECORDED_AT,RECORDED_AT);

        InOrder calls = inOrder(sensorRepository,readingRepository);
        calls.verify(sensorRepository).findByIdAndOwnerId(SENSOR_ID,OWNER_ID);
        calls.verify(readingRepository).findTop10BySensorIdAndSensorOwnerIdOrderByRecordedAtDescIdDesc(SENSOR_ID,OWNER_ID);
        calls.verifyNoMoreInteractions();
    }



    @ParameterizedTest
    @EnumSource(SensorType.class)
    void returnsEmptyViewsAndSnapshotForAnOwnedSensorWithoutReadings(SensorType type) {
        givenSensor(type,"UTC");
        givenReadings();

        assertThat(service.getRecentReadings(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS)).isEmpty();
        assertThat(service.getRecentReadingsSnapshot(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS))
                .isEqualTo(new RecentSensorReadingsDTO(SENSOR_ID,List.of()));
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void rejectsMissingAndForeignSensorsBeforeLookingUpReadings(boolean snapshot) {
        when(sensorRepository.findByIdAndOwnerId(-1L,OWNER_ID)).thenReturn(Optional.empty());
        when(sensorRepository.findByIdAndOwnerId(SENSOR_ID,OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> requestRead(snapshot,-1L))
                .isExactlyInstanceOf(SensorNotFoundException.class)
                .hasMessage("Sensor not found")
                .hasNoCause();

        assertThatThrownBy(() -> requestRead(snapshot,SENSOR_ID))
                .isExactlyInstanceOf(SensorNotFoundException.class)
                .hasMessage("Sensor not found")
                .hasNoCause();

        verifyNoInteractions(readingRepository);
    }



    @ParameterizedTest
    @CsvSource({
            "UTC,2026-01-15T13:00:00.123456Z,2026-01-15T13:00:00.123456Z,Z",
            "Europe/Istanbul,2026-01-15T13:00:00Z,2026-01-15T16:00:00+03:00,+03:00",
            "Asia/Kathmandu,2026-01-15T20:30:00Z,2026-01-16T02:15:00+05:45,+05:45",
            "America/New_York,2026-01-15T13:00:00Z,2026-01-15T08:00:00-05:00,-05:00",
            "America/New_York,2026-07-15T13:00:00Z,2026-07-15T09:00:00-04:00,-04:00"
    })
    void buildsSnapshotWithConvertedValueAndDateSpecificSensorOffset(String timezone,Instant recordedAt,String expectedTimestamp,String expectedOffset) {
        Sensor sensor = givenSensor(SensorType.TEMPERATURE,timezone);
        givenReadings(SensorReading.temperature(sensor,20.0,recordedAt));

        RecentSensorReadingsDTO result = service.getRecentReadingsSnapshot(SENSOR_ID,OWNER_ID,TemperatureUnit.FAHRENHEIT);

        SensorReadingSnapshotDTO expected = new SensorReadingSnapshotDTO(SensorType.TEMPERATURE,"Window",68.0,null,"°F",expectedTimestamp,timezone,expectedOffset);
        assertThat(result.sensorId()).isEqualTo(SENSOR_ID);
        assertThat(result.readings()).containsExactly(expected);
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void propagatesReadingLookupFailure(boolean snapshot) {
        givenSensor(SensorType.TEMPERATURE,"UTC");
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("Reading lookup failed");
        when(readingRepository.findTop10BySensorIdAndSensorOwnerIdOrderByRecordedAtDescIdDesc(SENSOR_ID,OWNER_ID)).thenThrow(failure);

        assertThatThrownBy(() -> requestRead(snapshot,SENSOR_ID)).isSameAs(failure);
    }



    private Sensor givenSensor(SensorType type,String timezone) {
        Sensor sensor = TestFixtures.sensor(type);
        sensor.updateDetails("Living room","Istanbul","Kadikoy","Window",timezone,CREATED_AT);
        when(sensorRepository.findByIdAndOwnerId(SENSOR_ID,OWNER_ID)).thenReturn(Optional.of(sensor));
        return sensor;
    }



    private void givenReadings(SensorReading... readings) {
        when(readingRepository.findTop10BySensorIdAndSensorOwnerIdOrderByRecordedAtDescIdDesc(SENSOR_ID,OWNER_ID))
                .thenReturn(List.of(readings));
    }



    private void requestRead(boolean snapshot,Long sensorId) {
        if (snapshot) {
            service.getRecentReadingsSnapshot(sensorId,OWNER_ID,TemperatureUnit.CELSIUS);
        } else {
            service.getRecentReadings(sensorId,OWNER_ID,TemperatureUnit.CELSIUS);
        }
    }
}