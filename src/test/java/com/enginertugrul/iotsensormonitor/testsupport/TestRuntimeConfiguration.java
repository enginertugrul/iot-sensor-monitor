package com.enginertugrul.iotsensormonitor.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.mock;

@TestConfiguration(proxyBeanMethods = false)
public class TestRuntimeConfiguration {

    public static final Instant TEST_INSTANT = Instant.parse("2026-01-15T12:00:00Z");

    @Bean
    @Primary
    Clock testClock() {
        return Clock.fixed(TEST_INSTANT,ZoneOffset.UTC);
    }

    @Bean
    JavaMailSender testMailSender() {
        return mock(JavaMailSender.class);
    }
}