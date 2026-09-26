package com.enginertugrul.iotsensormonitor;

import com.enginertugrul.iotsensormonitor.testsupport.PostgresTestConfiguration;
import com.enginertugrul.iotsensormonitor.testsupport.TestRuntimeConfiguration;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.flywaydb.core.api.output.ValidateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = IotSensorMonitorApplication.class,properties = "spring.config.location=classpath:/application-test.properties")
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class,TestRuntimeConfiguration.class,DatabaseMigrationIT.MigrationTestConfiguration.class})
class DatabaseMigrationIT {

    private static final List<String> EXPECTED_VERSIONS = List.of("1","2","3","4","5","6","7","8","9","10","11","12","13","14","15");

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void appliesAndValidatesEveryExistingMigration() {
        MigrationInfoService migrationInfo = flyway.info();

        assertThat(migrationInfo.applied())
                .extracting(migration -> migration.getVersion().getVersion())
                .containsExactlyElementsOf(EXPECTED_VERSIONS);
        assertThat(migrationInfo.applied()).allSatisfy(migration -> {
            assertThat(migration.getState()).isEqualTo(MigrationState.SUCCESS);
            assertThat(migration.getChecksum()).isNotNull();
        });
        assertThat(migrationInfo.pending()).isEmpty();
        assertThat(migrationInfo.current()).isNotNull();
        assertThat(migrationInfo.current().getVersion().getVersion()).isEqualTo("15");

        ValidateResult result = flyway.validateWithResult();

        assertThat(result.invalidMigrations).isEmpty();
        assertThat(result.validationSuccessful).isTrue();
        assertThat(result.validateCount).isEqualTo(EXPECTED_VERSIONS.size());
    }

    @Test
    void createsTheCurrentSchemaAndRemovesSupersededObjects() {
        assertThat(publicTableNames(jdbcTemplate)).containsExactlyInAnyOrder(
                "app_users","sensors","sensor_readings","alert_rules",
                "email_verification_challenges","password_reset_challenges",
                "hourly_sensor_summaries","daily_sensor_summaries",
                "sensor_rollup_checkpoints","flyway_schema_history");

        List<String> sensorColumns = jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'sensors'
                ORDER BY ordinal_position
                """,String.class);

        assertThat(sensorColumns)
                .contains("installation_location","first_reading_at","ingestion_token_hash")
                .doesNotContain("home_location","last_seen_at");
    }

    @Test
    void validatesTheMigratedSchemaAgainstAllCurrentEntityMappings() {
        assertThat(entityManagerFactory.isOpen()).isTrue();
        assertThat(entityManagerFactory.getProperties()).containsEntry("hibernate.hbm2ddl.auto","validate");
        assertThat(entityManagerFactory.getMetamodel().getEntities())
                .extracting(EntityType::getName)
                .containsExactlyInAnyOrder(
                        "AppUser","Sensor","SensorReading","AlertRule",
                        "EmailVerificationChallenge","PasswordResetChallenge",
                        "HourlySensorSummary","DailySensorSummary","SensorRollupCheckpoint");
    }

    @Test
    void repeatsMigrationWithoutApplyingChangesOrRewritingHistory() {
        List<Map<String,Object>> historyBefore = migrationHistory();
        List<String> tablesBefore = publicTableNames(jdbcTemplate);

        assertThat(historyBefore).hasSize(EXPECTED_VERSIONS.size());

        MigrateResult result = flyway.migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isZero();
        assertThat(migrationHistory()).containsExactlyElementsOf(historyBefore);
        assertThat(publicTableNames(jdbcTemplate)).containsExactlyElementsOf(tablesBefore);
        assertThat(flyway.info().pending()).isEmpty();

        ValidateResult validationResult = flyway.validateWithResult();

        assertThat(validationResult.invalidMigrations).isEmpty();
        assertThat(validationResult.validationSuccessful).isTrue();
    }

    private List<Map<String,Object>> migrationHistory() {
        return jdbcTemplate.queryForList("""
                SELECT installed_rank,version,description,type,script,checksum,
                       installed_by,installed_on,execution_time,success
                FROM public.flyway_schema_history
                ORDER BY installed_rank
                """);
    }

    private static List<String> publicTableNames(JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                ORDER BY table_name
                """,String.class);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MigrationTestConfiguration {

        @Bean
        FlywayMigrationStrategy migrationStrategy() {
            return flyway -> {
                JdbcTemplate jdbcTemplate = new JdbcTemplate(flyway.getConfiguration().getDataSource());

                assertThat(publicTableNames(jdbcTemplate))
                        .as("The first migration must start with an empty public schema")
                        .isEmpty();

                MigrationInfoService migrationInfo = flyway.info();

                assertThat(migrationInfo.applied()).isEmpty();
                assertThat(migrationInfo.current()).isNull();
                assertThat(migrationInfo.pending())
                        .extracting(MigrationInfo::getVersion)
                        .extracting(MigrationVersion::getVersion)
                        .containsExactlyElementsOf(EXPECTED_VERSIONS);

                MigrateResult result = flyway.migrate();

                assertThat(result.success).isTrue();
                assertThat(result.migrationsExecuted).isEqualTo(EXPECTED_VERSIONS.size());
                assertThat(result.targetSchemaVersion).isEqualTo("15");
            };
        }
    }
}