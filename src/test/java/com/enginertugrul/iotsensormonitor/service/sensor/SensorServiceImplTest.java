package com.enginertugrul.iotsensormonitor.service.sensor;

import com.enginertugrul.iotsensormonitor.dto.sensor.CreatedSensorDTO;
import com.enginertugrul.iotsensormonitor.dto.sensor.SensorCreateForm;
import com.enginertugrul.iotsensormonitor.dto.sensor.SensorListItemDTO;
import com.enginertugrul.iotsensormonitor.dto.sensor.SensorUpdateForm;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.exception.DuplicateSensorNameException;
import com.enginertugrul.iotsensormonitor.exception.SensorNotFoundException;
import com.enginertugrul.iotsensormonitor.exception.SensorTimezoneLockedException;
import com.enginertugrul.iotsensormonitor.repository.AppUserRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorRepository;
import com.enginertugrul.iotsensormonitor.security.ingestion.GeneratedSensorIngestionToken;
import com.enginertugrul.iotsensormonitor.security.ingestion.SensorIngestionTokenGenerator;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.UPDATED_AT;
import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;



@ExtendWith(MockitoExtension.class)
class SensorServiceImplTest {

    private static final Long OWNER_ID = 42L;
    private static final Long SENSOR_ID = 100L;
    private static final String RAW_TOKEN = "test-only-raw-ingestion-token";
    private static final String TOKEN_HASH = "a".repeat(64);
    private static final String NAME_CONSTRAINT = "uk_sensors_owner_name_lower";

    @Mock
    private SensorRepository sensorRepository;

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private SensorIngestionTokenGenerator tokenGenerator;

    private SensorServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SensorServiceImpl(sensorRepository,appUserRepository,tokenGenerator,Clock.fixed(UPDATED_AT,ZoneOffset.UTC));
    }



    @ParameterizedTest
    @EnumSource(SensorType.class)
    void createsNormalizedSensorWithStoredHashAndReturnedRawToken(SensorType type) {
        AppUser owner = owner();
        SensorCreateForm form = createForm(" Living room ");
        form.setType(type);
        form.setTimezone(" Europe/Istanbul ");

        when(appUserRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));
        when(tokenGenerator.generate()).thenReturn(new GeneratedSensorIngestionToken(RAW_TOKEN,TOKEN_HASH));
        when(sensorRepository.saveAndFlush(any(Sensor.class))).thenAnswer(invocation -> {
            Sensor sensor = invocation.getArgument(0);
            ReflectionTestUtils.setField(sensor,"id",SENSOR_ID);
            return sensor;
        });

        CreatedSensorDTO result = service.createSensor(OWNER_ID,form);

        ArgumentCaptor<Sensor> captor = ArgumentCaptor.forClass(Sensor.class);
        verify(sensorRepository).saveAndFlush(captor.capture());
        verify(sensorRepository).existsByOwnerIdAndNameIgnoreCase(OWNER_ID,"Living room");
        verify(tokenGenerator).generate();

        Sensor saved = captor.getValue();
        assertThat(saved.getOwner()).isSameAs(owner);
        assertThat(saved.getType()).isEqualTo(type);
        assertThat(saved.getName()).isEqualTo("Living room");
        assertThat(saved.getCity()).isEqualTo("Istanbul");
        assertThat(saved.getDistrict()).isEqualTo("Kadikoy");
        assertThat(saved.getInstallationLocation()).isEqualTo("Window");
        assertThat(saved.getTimezone()).isEqualTo("Europe/Istanbul");
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getFirstReadingAt()).isNull();
        assertThat(saved.getCreatedAt()).isEqualTo(UPDATED_AT);
        assertThat(saved.getUpdatedAt()).isEqualTo(UPDATED_AT);
        assertThat(saved.getIngestionTokenHash()).isEqualTo(TOKEN_HASH).isNotEqualTo(RAW_TOKEN);
        assertThat(result).isEqualTo(new CreatedSensorDTO(SENSOR_ID,"Living room",RAW_TOKEN));
    }



    @Test
    void rejectsMissingOwnerBeforeGeneratingTokenOrAccessingSensors() {
        when(appUserRepository.findById(OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createSensor(OWNER_ID,createForm("Living room")))
                .isExactlyInstanceOf(NoSuchElementException.class)
                .hasMessage("User not found");

        verifyNoInteractions(sensorRepository,tokenGenerator);
    }



    @Test
    void rejectsDuplicateNameBeforeGeneratingTokenOrSaving() {
        when(appUserRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner()));
        when(sensorRepository.existsByOwnerIdAndNameIgnoreCase(OWNER_ID,"Living room")).thenReturn(true);

        assertThatThrownBy(() -> service.createSensor(OWNER_ID,createForm(" Living room ")))
                .isExactlyInstanceOf(DuplicateSensorNameException.class)
                .hasMessage("You already have a sensor with this name");

        verifyNoInteractions(tokenGenerator);
        verify(sensorRepository,never()).saveAndFlush(any(Sensor.class));
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void translatesOwnerNameConstraintFailureDuringCreateAndUpdate(boolean updating) {
        DataIntegrityViolationException failure = constraintFailure(NAME_CONSTRAINT);
        Runnable operation = failingPersistenceOperation(updating,failure);

        assertThatThrownBy(operation::run)
                .isExactlyInstanceOf(DuplicateSensorNameException.class)
                .hasMessage("You already have a sensor with this name")
                .hasNoCause();
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void preservesUnrelatedConstraintFailureDuringCreateAndUpdate(boolean updating) {
        DataIntegrityViolationException failure = constraintFailure("uk_sensors_ingestion_token_hash");
        Runnable operation = failingPersistenceOperation(updating,failure);

        assertThatThrownBy(operation::run).isSameAs(failure);
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void doesNotTranslatePersistenceFailureBasedOnlyOnMessage(boolean updating) {
        DataIntegrityViolationException failure = new DataIntegrityViolationException(NAME_CONSTRAINT);
        Runnable operation = failingPersistenceOperation(updating,failure);

        assertThatThrownBy(operation::run).isSameAs(failure);
    }



    @Test
    void mapsOwnerScopedSensorListWithoutChangingRepositoryOrder() {
        Sensor first = ownedSensor();
        Sensor second = new Sensor(owner(),SensorType.MOTION,"Hall","Ankara","Cankaya","Door","Europe/Istanbul",CREATED_AT);
        ReflectionTestUtils.setField(second,"id",101L);
        second.deactivate(UPDATED_AT);
        when(sensorRepository.findByOwnerIdOrderByCreatedAtDesc(OWNER_ID)).thenReturn(List.of(first,second));

        assertThat(service.getSensorsForUser(OWNER_ID)).containsExactly(
                new SensorListItemDTO(SENSOR_ID,"Living room",SensorType.TEMPERATURE,"Istanbul","Kadikoy","Window","UTC",true),
                new SensorListItemDTO(101L,"Hall",SensorType.MOTION,"Ankara","Cankaya","Door","Europe/Istanbul",false));

        verify(sensorRepository).findByOwnerIdOrderByCreatedAtDesc(OWNER_ID);
    }



    @Test
    void returnsEmptyListWhenOwnerHasNoSensors() {
        when(sensorRepository.findByOwnerIdOrderByCreatedAtDesc(OWNER_ID)).thenReturn(List.of());

        assertThat(service.getSensorsForUser(OWNER_ID)).isEmpty();
    }



    @Test
    void returnsOwnedSensorAndMapsIndependentUpdateForm() {
        Sensor sensor = ownedSensor();
        when(sensorRepository.findByIdAndOwnerId(SENSOR_ID,OWNER_ID)).thenReturn(Optional.of(sensor));

        assertThat(service.getSensorForUser(SENSOR_ID,OWNER_ID)).isSameAs(sensor);

        SensorUpdateForm form = service.getSensorUpdateForm(SENSOR_ID,OWNER_ID);
        assertThat(form.getName()).isEqualTo("Living room");
        assertThat(form.getCity()).isEqualTo("Istanbul");
        assertThat(form.getDistrict()).isEqualTo("Kadikoy");
        assertThat(form.getInstallationLocation()).isEqualTo("Window");
        assertThat(form.getTimezone()).isEqualTo("UTC");

        form.setName("Changed only in the form");

        assertThat(sensor.getName()).isEqualTo("Living room");
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void updatesDetailsAndPreservesIdentityTokenAndReadingHistory(boolean hasHistory) {
        Sensor sensor = ownedSensor();
        if (hasHistory) {
            sensor.recordFirstReading(CREATED_AT.plusSeconds(30),CREATED_AT.plusSeconds(30));
        }
        String timezone = hasHistory ? "UTC" : "Europe/Istanbul";
        when(sensorRepository.findByIdAndOwnerIdForUpdate(SENSOR_ID,OWNER_ID)).thenReturn(Optional.of(sensor));

        service.updateSensor(SENSOR_ID,OWNER_ID,updateForm(" Renamed sensor "," " + timezone + " "));

        assertThat(sensor.getName()).isEqualTo("Renamed sensor");
        assertThat(sensor.getCity()).isEqualTo("Ankara");
        assertThat(sensor.getDistrict()).isEqualTo("Cankaya");
        assertThat(sensor.getInstallationLocation()).isEqualTo("Shelf");
        assertThat(sensor.getTimezone()).isEqualTo(timezone);
        assertThat(sensor.getUpdatedAt()).isEqualTo(UPDATED_AT);
        assertThat(sensor.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(sensor.getId()).isEqualTo(SENSOR_ID);
        assertThat(sensor.getOwner().getId()).isEqualTo(OWNER_ID);
        assertThat(sensor.getType()).isEqualTo(SensorType.TEMPERATURE);
        assertThat(sensor.getIngestionTokenHash()).isEqualTo(TOKEN_HASH);
        assertThat(sensor.isActive()).isTrue();
        assertThat(sensor.getFirstReadingAt()).isEqualTo(hasHistory ? CREATED_AT.plusSeconds(30) : null);

        verify(sensorRepository).existsByOwnerIdAndNameIgnoreCaseAndIdNot(OWNER_ID,"Renamed sensor",SENSOR_ID);
        verify(sensorRepository).flush();
        verifyNoInteractions(tokenGenerator);
    }



    @Test
    void rejectsDuplicateUpdateBeforeChangingSensor() {
        Sensor sensor = ownedSensor();
        when(sensorRepository.findByIdAndOwnerIdForUpdate(SENSOR_ID,OWNER_ID)).thenReturn(Optional.of(sensor));
        when(sensorRepository.existsByOwnerIdAndNameIgnoreCaseAndIdNot(OWNER_ID,"Taken",SENSOR_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.updateSensor(SENSOR_ID,OWNER_ID,updateForm(" Taken ","Europe/Istanbul")))
                .isExactlyInstanceOf(DuplicateSensorNameException.class);

        assertThat(sensor.getName()).isEqualTo("Living room");
        assertThat(sensor.getCity()).isEqualTo("Istanbul");
        assertThat(sensor.getTimezone()).isEqualTo("UTC");
        assertThat(sensor.getUpdatedAt()).isEqualTo(CREATED_AT);
        verify(sensorRepository,never()).flush();
    }



    @Test
    void rejectsTimezoneChangeAfterFirstReadingWithoutChangingDetails() {
        Sensor sensor = ownedSensor();
        sensor.recordFirstReading(CREATED_AT.plusSeconds(30),CREATED_AT.plusSeconds(30));
        when(sensorRepository.findByIdAndOwnerIdForUpdate(SENSOR_ID,OWNER_ID)).thenReturn(Optional.of(sensor));

        assertThatThrownBy(() -> service.updateSensor(SENSOR_ID,OWNER_ID,updateForm("Renamed sensor","Europe/Istanbul")))
                .isExactlyInstanceOf(SensorTimezoneLockedException.class);

        assertThat(sensor.getName()).isEqualTo("Living room");
        assertThat(sensor.getTimezone()).isEqualTo("UTC");
        assertThat(sensor.getFirstReadingAt()).isEqualTo(CREATED_AT.plusSeconds(30));
        assertThat(sensor.getUpdatedAt()).isEqualTo(CREATED_AT.plusSeconds(30));
        verify(sensorRepository,never()).flush();
    }



    @Test
    void rejectsNullUpdateFormBeforeRepositoryAccess() {
        assertThatThrownBy(() -> service.updateSensor(SENSOR_ID,OWNER_ID,null))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("form must not be null");

        verifyNoInteractions(sensorRepository,appUserRepository,tokenGenerator);
    }



    @ParameterizedTest
    @CsvSource({"true,true","true,false","false,true","false,false"})
    void changesActivationOnlyWhenNecessaryUsingLockedOwnerLookup(boolean initiallyActive,boolean requestedActive) {
        Sensor sensor = ownedSensor();
        if (!initiallyActive) {
            sensor.deactivate(CREATED_AT);
        }
        when(sensorRepository.findByIdAndOwnerIdForUpdate(SENSOR_ID,OWNER_ID)).thenReturn(Optional.of(sensor));

        if (requestedActive) {
            service.activateSensor(SENSOR_ID,OWNER_ID);
        } else {
            service.deactivateSensor(SENSOR_ID,OWNER_ID);
        }

        assertThat(sensor.isActive()).isEqualTo(requestedActive);
        assertThat(sensor.getUpdatedAt()).isEqualTo(initiallyActive == requestedActive ? CREATED_AT : UPDATED_AT);
        assertThat(sensor.getIngestionTokenHash()).isEqualTo(TOKEN_HASH);
        assertThat(sensor.getCreatedAt()).isEqualTo(CREATED_AT);
        verify(sensorRepository).findByIdAndOwnerIdForUpdate(SENSOR_ID,OWNER_ID);
    }



    @Test
    void deletesTheSensorReturnedByOwnerScopedLookup() {
        Sensor sensor = ownedSensor();
        when(sensorRepository.findByIdAndOwnerId(SENSOR_ID,OWNER_ID)).thenReturn(Optional.of(sensor));

        service.deleteSensor(SENSOR_ID,OWNER_ID);

        verify(sensorRepository).delete(sensor);
        verifyNoInteractions(appUserRepository,tokenGenerator);
    }



    @Test
    void returnsSameNotFoundContractForEveryUnavailableOwnedSensorOperation() {
        when(sensorRepository.findByIdAndOwnerId(SENSOR_ID,OWNER_ID)).thenReturn(Optional.empty());
        when(sensorRepository.findByIdAndOwnerIdForUpdate(SENSOR_ID,OWNER_ID)).thenReturn(Optional.empty());

        List<Runnable> operations = List.of(
                () -> service.getSensorForUser(SENSOR_ID,OWNER_ID),
                () -> service.getSensorUpdateForm(SENSOR_ID,OWNER_ID),
                () -> service.updateSensor(SENSOR_ID,OWNER_ID,updateForm("Changed","UTC")),
                () -> service.activateSensor(SENSOR_ID,OWNER_ID),
                () -> service.deactivateSensor(SENSOR_ID,OWNER_ID),
                () -> service.deleteSensor(SENSOR_ID,OWNER_ID));

        for (Runnable operation : operations) {
            assertThatThrownBy(operation::run)
                    .isExactlyInstanceOf(SensorNotFoundException.class)
                    .hasMessage("Sensor not found")
                    .hasNoCause();
        }

        verify(sensorRepository,never()).delete(any(Sensor.class));
        verify(sensorRepository,never()).saveAndFlush(any(Sensor.class));
        verify(sensorRepository,never()).flush();
        verifyNoInteractions(appUserRepository,tokenGenerator);
    }



    private Runnable failingPersistenceOperation(boolean updating,DataIntegrityViolationException failure) {
        if (updating) {
            when(sensorRepository.findByIdAndOwnerIdForUpdate(SENSOR_ID,OWNER_ID)).thenReturn(Optional.of(ownedSensor()));
            doThrow(failure).when(sensorRepository).flush();
            return () -> service.updateSensor(SENSOR_ID,OWNER_ID,updateForm("Changed","UTC"));
        }

        when(appUserRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner()));
        when(tokenGenerator.generate()).thenReturn(new GeneratedSensorIngestionToken(RAW_TOKEN,TOKEN_HASH));
        when(sensorRepository.saveAndFlush(any(Sensor.class))).thenThrow(failure);
        return () -> service.createSensor(OWNER_ID,createForm("Living room"));
    }



    private static DataIntegrityViolationException constraintFailure(String constraintName) {
        ConstraintViolationException cause = new ConstraintViolationException("Constraint violation",new SQLException("duplicate key","23505"),constraintName);
        return new DataIntegrityViolationException("Could not persist sensor",cause);
    }



    private static AppUser owner() {
        AppUser owner = user();
        ReflectionTestUtils.setField(owner,"id",OWNER_ID);
        return owner;
    }



    private static Sensor ownedSensor() {
        Sensor sensor = new Sensor(owner(),SensorType.TEMPERATURE,"Living room","Istanbul","Kadikoy","Window","UTC",CREATED_AT);
        sensor.assignIngestionTokenHash(TOKEN_HASH,CREATED_AT);
        ReflectionTestUtils.setField(sensor,"id",SENSOR_ID);
        return sensor;
    }



    private static SensorCreateForm createForm(String name) {
        SensorCreateForm form = new SensorCreateForm();
        form.setName(name);
        form.setCity(" Istanbul ");
        form.setDistrict(" Kadikoy ");
        form.setInstallationLocation(" Window ");
        form.setTimezone(" UTC ");
        return form;
    }



    private static SensorUpdateForm updateForm(String name,String timezone) {
        SensorUpdateForm form = new SensorUpdateForm();
        form.setName(name);
        form.setCity(" Ankara ");
        form.setDistrict(" Cankaya ");
        form.setInstallationLocation(" Shelf ");
        form.setTimezone(timezone);
        return form;
    }
}