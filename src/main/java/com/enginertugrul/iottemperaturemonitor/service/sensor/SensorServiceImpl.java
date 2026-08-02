package com.enginertugrul.iottemperaturemonitor.service.sensor;

import com.enginertugrul.iottemperaturemonitor.dto.sensor.CreatedSensorDTO;
import com.enginertugrul.iottemperaturemonitor.dto.sensor.SensorForm;
import com.enginertugrul.iottemperaturemonitor.dto.sensor.SensorListItemDTO;
import com.enginertugrul.iottemperaturemonitor.dto.sensor.SensorUpdateForm;
import com.enginertugrul.iottemperaturemonitor.entity.sensor.Sensor;
import com.enginertugrul.iottemperaturemonitor.entity.user.AppUser;
import com.enginertugrul.iottemperaturemonitor.exception.DuplicateSensorNameException;
import com.enginertugrul.iottemperaturemonitor.exception.SensorNotFoundException;
import com.enginertugrul.iottemperaturemonitor.repository.SensorRepository;
import com.enginertugrul.iottemperaturemonitor.repository.AppUserRepository;
import com.enginertugrul.iottemperaturemonitor.security.ingestion.GeneratedSensorIngestionToken;
import com.enginertugrul.iottemperaturemonitor.security.ingestion.SensorIngestionTokenGenerator;
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
                sensorForm.getHomeLocation(),
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
        form.setHomeLocation(sensor.getHomeLocation());
        form.setTimezone(sensor.getTimezone());

        return form;
    }





    @Override
    @Transactional
    public void updateSensor(Long sensorId, Long ownerId, SensorUpdateForm sensorUpdateForm) {

        SensorUpdateForm requiredForm = Objects.requireNonNull(sensorUpdateForm,"sensorUpdateForm must not be null");

        Sensor sensor = getOwnedSensor(sensorId, ownerId);


        if(sensorRepository.existsByOwnerIdAndNameIgnoreCaseAndIdNot(ownerId, requiredForm.getName(), sensorId) ) {
            throw new DuplicateSensorNameException();
        }

        sensor.updateDetails(
                requiredForm.getName(),
                requiredForm.getCity(),
                requiredForm.getDistrict(),
                requiredForm.getHomeLocation(),
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






    private SensorListItemDTO toListItem(Sensor sensor) {
        return new SensorListItemDTO(
                sensor.getId(),
                sensor.getName(),
                sensor.getType(),
                sensor.getCity(),
                sensor.getDistrict(),
                sensor.getHomeLocation(),
                sensor.getTimezone(),
                sensor.isActive()
        );
    }



}