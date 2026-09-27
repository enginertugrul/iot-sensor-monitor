package com.enginertugrul.iotsensormonitor.service.reading.ingestion;

import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.exception.InactiveSensorException;
import com.enginertugrul.iotsensormonitor.exception.InvalidSensorReadingException;
import com.enginertugrul.iotsensormonitor.exception.InvalidSensorTokenException;
import com.enginertugrul.iotsensormonitor.repository.SensorRepository;
import com.enginertugrul.iotsensormonitor.security.ingestion.SecureRandomSensorIngestionTokenGenerator;
import com.enginertugrul.iotsensormonitor.security.ingestion.SensorIngestionTokenGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Optional;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.UPDATED_AT;
import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.sensor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;



@ExtendWith(MockitoExtension.class)
class SensorIngestionAccessServiceTest {

    private static final String RAW_TOKEN = " test-only-ingestion-token ";
    private static final String TOKEN_HASH = "a".repeat(64);

    @Mock
    private SensorRepository sensorRepository;

    @Mock
    private SensorIngestionTokenGenerator tokenGenerator;

    private SensorIngestionAccessService service;

    @BeforeEach
    void setUp() {
        service = new SensorIngestionAccessService(sensorRepository,tokenGenerator);
    }



    @ParameterizedTest
    @EnumSource(SensorType.class)
    void returnsMatchingActiveSensorUsingLockedLookupByTokenHash(SensorType type) {
        Sensor sensor = registeredSensor(type);
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(sensorRepository.findByIngestionTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.of(sensor));

        Sensor result = service.requireActiveSensor(RAW_TOKEN,type);

        assertThat(result).isSameAs(sensor);
        assertThat(sensor.isActive()).isTrue();
        assertThat(sensor.getType()).isEqualTo(type);
        assertThat(sensor.getIngestionTokenHash()).isEqualTo(TOKEN_HASH);
        assertThat(sensor.getFirstReadingAt()).isNull();
        assertThat(sensor.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(sensor.getUpdatedAt()).isEqualTo(CREATED_AT);

        InOrder accessOrder = inOrder(tokenGenerator,sensorRepository);
        accessOrder.verify(tokenGenerator).hash(RAW_TOKEN);
        accessOrder.verify(sensorRepository).findByIngestionTokenHashForUpdate(TOKEN_HASH);
        verifyNoMoreInteractions(tokenGenerator,sensorRepository);
    }



    @ParameterizedTest
    @EnumSource(SensorType.class)
    void rejectsTokenWhoseHashDoesNotIdentifyASensor(SensorType expectedType) {
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(sensorRepository.findByIngestionTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireActiveSensor(RAW_TOKEN,expectedType))
                .isExactlyInstanceOf(InvalidSensorTokenException.class)
                .hasMessage("Invalid sensor token")
                .hasNoCause();
    }



    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ","\t\r\n","\u2003"})
    void rejectsBlankTokensThroughRealGeneratorBeforeRepositoryAccess(String rawToken) {
        SensorIngestionAccessService serviceWithRealGenerator = new SensorIngestionAccessService(sensorRepository,new SecureRandomSensorIngestionTokenGenerator());

        assertThatThrownBy(() -> serviceWithRealGenerator.requireActiveSensor(rawToken,SensorType.TEMPERATURE))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("sensor ingestion token must not be blank");

        verifyNoInteractions(sensorRepository);
    }



    @ParameterizedTest
    @EnumSource(SensorType.class)
    void rejectsInactiveSensorBeforeCheckingExpectedType(SensorType registeredType) {
        Sensor sensor = registeredSensor(registeredType);
        sensor.deactivate(UPDATED_AT);
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(sensorRepository.findByIngestionTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.of(sensor));

        for (SensorType expectedType : SensorType.values()) {
            assertThatThrownBy(() -> service.requireActiveSensor(RAW_TOKEN,expectedType))
                    .isExactlyInstanceOf(InactiveSensorException.class)
                    .hasMessage("Sensor is not active")
                    .hasNoCause();
        }

        assertThat(sensor.isActive()).isFalse();
        assertThat(sensor.getUpdatedAt()).isEqualTo(UPDATED_AT);
        assertThat(sensor.getIngestionTokenHash()).isEqualTo(TOKEN_HASH);
    }



    @ParameterizedTest
    @CsvSource({
            "TEMPERATURE,HUMIDITY",
            "TEMPERATURE,MOTION",
            "HUMIDITY,TEMPERATURE",
            "HUMIDITY,MOTION",
            "MOTION,TEMPERATURE",
            "MOTION,HUMIDITY"
    })
    void rejectsEveryMismatchedSensorType(SensorType registeredType,SensorType expectedType) {
        Sensor sensor = registeredSensor(registeredType);
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(sensorRepository.findByIngestionTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.of(sensor));

        assertThatThrownBy(() -> service.requireActiveSensor(RAW_TOKEN,expectedType))
                .isExactlyInstanceOf(InvalidSensorReadingException.class)
                .hasMessage("Reading does not match the registered sensor type")
                .hasNoCause();

        assertThat(sensor.isActive()).isTrue();
        assertThat(sensor.getType()).isEqualTo(registeredType);
        assertThat(sensor.getUpdatedAt()).isEqualTo(CREATED_AT);
    }



    @Test
    void propagatesRepositoryFailureWithoutReportingAnInvalidToken() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("Database unavailable");
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(sensorRepository.findByIngestionTokenHashForUpdate(TOKEN_HASH)).thenThrow(failure);

        assertThatThrownBy(() -> service.requireActiveSensor(RAW_TOKEN,SensorType.TEMPERATURE))
                .isSameAs(failure);
    }



    private static Sensor registeredSensor(SensorType type) {
        Sensor sensor = sensor(type);
        sensor.assignIngestionTokenHash(TOKEN_HASH,CREATED_AT);
        return sensor;
    }
}