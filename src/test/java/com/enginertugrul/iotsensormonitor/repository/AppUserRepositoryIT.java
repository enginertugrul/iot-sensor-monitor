package com.enginertugrul.iotsensormonitor.repository;

import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.entity.user.PreferredLanguage;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.testsupport.PostgresTestConfiguration;
import com.enginertugrul.iotsensormonitor.testsupport.TestRuntimeConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.UPDATED_AT;
import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


@SpringBootTest(properties = "spring.config.location=classpath:/application-test.properties")
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class,TestRuntimeConfiguration.class})
@Transactional
class AppUserRepositoryIT {

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;



    @Test
    void persistsAndReloadsUserStateThroughNormalizedEmailLookup() {
        AppUser user = new AppUser(" OWNER@Example.COM ","stored-password-hash",PreferredLanguage.TURKISH,TemperatureUnit.FAHRENHEIT,"Europe/Istanbul",CREATED_AT);
        user.verifyEmail(UPDATED_AT);
        user.disable(UPDATED_AT);

        Long userId = appUserRepository.saveAndFlush(user).getId();
        entityManager.clear();

        AppUser reloaded = appUserRepository.findByEmail("owner@example.com").orElseThrow();

        assertThat(userId).isPositive();
        assertThat(reloaded.getId()).isEqualTo(userId);
        assertThat(reloaded.getEmail()).isEqualTo("owner@example.com");
        assertThat(reloaded.getPasswordHash()).isEqualTo("stored-password-hash");
        assertThat(reloaded.getPreferredLanguage()).isEqualTo(PreferredLanguage.TURKISH);
        assertThat(reloaded.getPreferredTemperatureUnit()).isEqualTo(TemperatureUnit.FAHRENHEIT);
        assertThat(reloaded.getPreferredTimezone()).isEqualTo("Europe/Istanbul");
        assertThat(reloaded.isEnabled()).isFalse();
        assertThat(reloaded.getEmailVerifiedAt()).isEqualTo(UPDATED_AT);
        assertThat(reloaded.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(UPDATED_AT);

        assertThat(appUserRepository.existsByEmail("owner@example.com")).isTrue();
        assertThat(appUserRepository.existsByEmail("missing@example.com")).isFalse();
        assertThat(appUserRepository.findByEmail("missing@example.com")).isEmpty();
    }



    @Test
    void findsUsersForUpdateByIdAndNormalizedEmail() {
        AppUser first = appUserRepository.saveAndFlush(user());
        AppUser second = appUserRepository.saveAndFlush(new AppUser("second@example.com","second-password-hash",CREATED_AT));
        entityManager.clear();

        assertThat(appUserRepository.findByIdForUpdate(first.getId()).map(AppUser::getId)).contains(first.getId());
        assertThat(appUserRepository.findByIdForUpdate(second.getId()).map(AppUser::getId)).contains(second.getId());
        assertThat(appUserRepository.findByIdForUpdate(-1L)).isEmpty();

        assertThat(appUserRepository.findByEmailForUpdate("owner@example.com").map(AppUser::getId)).contains(first.getId());
        assertThat(appUserRepository.findByEmailForUpdate("second@example.com").map(AppUser::getId)).contains(second.getId());
        assertThat(appUserRepository.findByEmailForUpdate("missing@example.com")).isEmpty();
    }



    @Test
    void rejectsCaseInsensitiveDuplicateEmailThroughDirectSql() {
        appUserRepository.saveAndFlush(user());

        Timestamp createdAt = Timestamp.from(CREATED_AT);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO app_users (email,password_hash,created_at,updated_at)
                VALUES (?,?,?,?)
                ""","OWNER@EXAMPLE.COM","another-password-hash",createdAt,createdAt))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_app_users_email_lower");
    }
}