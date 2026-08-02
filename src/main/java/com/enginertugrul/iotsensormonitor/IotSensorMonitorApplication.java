package com.enginertugrul.iotsensormonitor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class IotSensorMonitorApplication {

    public static void main(String[] args) {
        SpringApplication.run(IotSensorMonitorApplication.class, args);
    }

}
