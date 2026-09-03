package com.enginertugrul.iotsensormonitor.service.sensor;

import com.enginertugrul.iotsensormonitor.dto.sensor.CreatedSensorDTO;
import com.enginertugrul.iotsensormonitor.dto.sensor.SensorForm;
import com.enginertugrul.iotsensormonitor.dto.sensor.SensorListItemDTO;
import com.enginertugrul.iotsensormonitor.dto.sensor.SensorUpdateForm;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.exception.DuplicateSensorNameException;
import com.enginertugrul.iotsensormonitor.exception.SensorNotFoundException;
import com.enginertugrul.iotsensormonitor.repository.SensorRepository;
import com.enginertugrul.iotsensormonitor.repository.AppUserRepository;
import com.enginertugrul.iotsensormonitor.security.ingestion.GeneratedSensorIngestionToken;
import com.enginertugrul.iotsensormonitor.security.ingestion.SensorIngestionTokenGenerator;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;





@Service
public class SensorServiceImpl implements SensorService {


    private static final String SENSOR_OWNER_NAME_UNIQUE_INDEX = "uk_sensors_owner_name_lower";


    private final SensorRepository sensorRepository;
    private final AppUserRepository appUserRepository;
    private final SensorIngestionTokenGenerator sensorIngestionTokenGenerator;

    public SensorServiceImpl(SensorRepository sensorRepository, AppUserRepository appUserRepository, SensorIngestionTokenGenerator sensorIngestionTokenGenerator) {
        this.sensorRepository = sensorRepository;
        this.appUserRepository = appUserRepository;
        this.sensorIngestionTokenGenerator = sensorIngestionTokenGenerator;
    }






    @Override
    @Transactional
    public CreatedSensorDTO createSensor(Long ownerId, SensorForm sensorForm) {
        AppUser owner = appUserRepository.findById(ownerId)
                .orElseThrow(() -> new NoSuchElementException("User not found"));

        String requestedName = sensorForm.getName().trim();

        if (sensorRepository.existsByOwnerIdAndNameIgnoreCase(ownerId, requestedName)) {
            throw new DuplicateSensorNameException();
        }

        Sensor sensor = new Sensor(
                owner,
                sensorForm.getType(),
                requestedName,
                sensorForm.getCity(),
                sensorForm.getDistrict(),
                sensorForm.getInstallationLocation(),
                sensorForm.getTimezone()
        );

        GeneratedSensorIngestionToken generatedToken = sensorIngestionTokenGenerator.generate();
        sensor.assignIngestionTokenHash(generatedToken.tokenHash());

        Sensor savedSensor;


        try {
            savedSensor = sensorRepository.saveAndFlush(sensor);
        } catch (DataIntegrityViolationException e) {
            throw translateSensorPersistenceException(e);
        }



        return new CreatedSensorDTO(savedSensor.getId() ,
                savedSensor.getName(),
                generatedToken.rawToken());

    }





    @Override
    @Transactional(readOnly = true)
    public List<SensorListItemDTO> getSensorsForUser(Long ownerId) {
        return sensorRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId)
                .stream()
                .map(this::toListItem)
                .toList();
    }





    @Override
    @Transactional(readOnly = true)
    public Sensor getSensorForUser(Long sensorId, Long ownerId) {
        return getOwnedSensor(sensorId,ownerId);
    }





    @Override
    @Transactional(readOnly = true)
    public SensorUpdateForm getSensorUpdateForm(Long sensorId, Long ownerId) {

        Sensor sensor = getOwnedSensor(sensorId, ownerId);

        SensorUpdateForm form = new SensorUpdateForm();

        form.setName(sensor.getName());
        form.setCity(sensor.getCity());
        form.setDistrict(sensor.getDistrict());
        form.setInstallationLocation(sensor.getInstallationLocation());
        form.setTimezone(sensor.getTimezone());

        return form;
    }





    @Override
    @Transactional
    public void updateSensor(Long sensorId, Long ownerId, SensorUpdateForm form) {

        Objects.requireNonNull(form,"form must not be null");

        Sensor sensor = getOwnedSensorForUpdate(sensorId, ownerId);
        String requestedName = form.getName().trim();

        if(sensorRepository.existsByOwnerIdAndNameIgnoreCaseAndIdNot(ownerId, form.getName(), sensorId) ) {
            throw new DuplicateSensorNameException();
        }

        sensor.updateDetails(
                requestedName,
                form.getCity(),
                form.getDistrict(),
                form.getInstallationLocation(),
                form.getTimezone()
        );

        try {
            sensorRepository.flush();
        }catch (DataIntegrityViolationException e) {
            throw translateSensorPersistenceException(e);
        }


    }





    @Override
    @Transactional
    public void deleteSensor(Long sensorId, Long ownerId) {
        Sensor sensor = getOwnedSensor(sensorId, ownerId);
        sensorRepository.delete(sensor);
    }





    @Override
    @Transactional(readOnly = true)
    public String getDefaultTimezoneForUser(Long ownerId) {
        return appUserRepository.findById(ownerId)
                .orElseThrow(() -> new NoSuchElementException("User not found"))
                .getPreferredTimezone();
    }






    private Sensor getOwnedSensor(Long sensorId, Long ownerId) {
        return sensorRepository.findByIdAndOwnerId(sensorId, ownerId)
                .orElseThrow(SensorNotFoundException::new);
    }



    private Sensor getOwnedSensorForUpdate(Long sensorId,Long ownerId) {
        return sensorRepository.findByIdAndOwnerIdForUpdate(sensorId,ownerId)
                .orElseThrow(SensorNotFoundException::new);
    }



    private RuntimeException translateSensorPersistenceException(DataIntegrityViolationException exception) {

        if ( exception.getCause() instanceof ConstraintViolationException constraintViolation
                && SENSOR_OWNER_NAME_UNIQUE_INDEX.equals(constraintViolation.getConstraintName()) ) {

            return new DuplicateSensorNameException();
        }

        return exception;
    }


    private SensorListItemDTO toListItem(Sensor sensor) {
        return new SensorListItemDTO(
                sensor.getId(),
                sensor.getName(),
                sensor.getType(),
                sensor.getCity(),
                sensor.getDistrict(),
                sensor.getInstallationLocation(),
                sensor.getTimezone(),
                sensor.isActive()
        );
    }



}