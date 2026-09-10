package com.enginertugrul.iotsensormonitor.dto.sensor;



public record CreatedSensorDTO(Long sensorId, String sensorName , String rawIngestionToken) {
    @Override
    public String toString() {
        return "CreatedSensorDTO[sensorId=" + sensorId + ", sensorName=" + sensorName + ", rawIngestionToken=[REDACTED]]";
    }
}
