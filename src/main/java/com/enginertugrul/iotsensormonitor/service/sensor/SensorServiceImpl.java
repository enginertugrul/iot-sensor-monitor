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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;





@Service
public class SensorServiceImpl implements SensorService {

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

        Sensor savedSensor = sensorRepository.save(sensor);

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
        return sensorRepository.findByIdAndOwnerId(sensorId, ownerId)
                .orElseThrow(() -> new NoSuchElementException("Sensor not found"));
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
    public void updateSensor(Long sensorId, Long ownerId, SensorUpdateForm sensorUpdateForm) {

        SensorUpdateForm requiredForm = Objects.requireNonNull(sensorUpdateForm,"sensorUpdateForm must not be null");

        Sensor sensor = getOwnedSensorForUpdate(sensorId, ownerId);


        if(sensorRepository.existsByOwnerIdAndNameIgnoreCaseAndIdNot(ownerId, requiredForm.getName(), sensorId) ) {
            throw new DuplicateSensorNameException();
        }

        sensor.updateDetails(
                requiredForm.getName(),
                requiredForm.getCity(),
                requiredForm.getDistrict(),
                requiredForm.getInstallationLocation(),
                requiredForm.getTimezone()
        );
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