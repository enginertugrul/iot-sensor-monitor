package com.enginertugrul.iotsensormonitor;

import com.enginertugrul.iotsensormonitor.scheduler.SensorReadingStreamScheduler;
import com.enginertugrul.iotsensormonitor.testsupport.PostgresTestConfiguration;
import com.enginertugrul.iotsensormonitor.testsupport.TestRuntimeConfiguration;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockingDetails;

@SpringBootTest(properties = "spring.config.location=classpath:/application-test.properties")
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class,TestRuntimeConfiguration.class})
class IotSensorMonitorApplicationIT {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PostgreSQLContainer postgresContainer;

    @Autowired
    private Flyway flyway;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private Clock clock;

    @Autowired
    private JavaMailSender mailSender;

    @Test
    void connectsApplicationAndFlywayToContainerizedPostgres() throws SQLException {
        assertThat(postgresContainer.isRunning()).isTrue();

        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(17);
            assertThat(connection.getMetaData().getURL()).isEqualTo(postgresContainer.getJdbcUrl());
            assertThat(connection.getCatalog()).isEqualTo(postgresContainer.getDatabaseName());
        }

        try (Connection connection = flyway.getConfiguration().getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(postgresContainer.getJdbcUrl());
        }
    }

    @Test
    void startsWithFlywayMigrationsAppliedAndHibernateSchemaValidated() {
        assertThat(flyway.info().applied()).isNotEmpty();
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(entityManagerFactory.isOpen()).isTrue();
        assertThat(entityManagerFactory.getProperties()).containsEntry("hibernate.hbm2ddl.auto","validate");
        assertThat(entityManagerFactory.getMetamodel().getEntities()).isNotEmpty();
    }

    @Test
    void usesIsolatedRuntimeWithBackgroundSchedulingDisabled() {
        assertThat(applicationContext.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(SensorReadingStreamScheduler.class)).isEmpty();
        assertThat(clock.instant()).isEqualTo(TestRuntimeConfiguration.TEST_INSTANT);
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(mockingDetails(mailSender).isMock()).isTrue();
    }
}