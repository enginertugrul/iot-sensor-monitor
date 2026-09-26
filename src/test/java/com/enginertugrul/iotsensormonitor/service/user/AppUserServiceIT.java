package com.enginertugrul.iotsensormonitor.service.user;

import com.enginertugrul.iotsensormonitor.dto.auth.RegisterUserForm;
import com.enginertugrul.iotsensormonitor.dto.user.AccountSettingsPageDTO;
import com.enginertugrul.iotsensormonitor.dto.user.UserPreferencesForm;
import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.entity.user.PreferredLanguage;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.exception.EmailAlreadyRegisteredException;
import com.enginertugrul.iotsensormonitor.repository.AppUserRepository;
import com.enginertugrul.iotsensormonitor.service.user.verification.EmailVerificationCodeDelivery;
import com.enginertugrul.iotsensormonitor.testsupport.PostgresTestConfiguration;
import com.enginertugrul.iotsensormonitor.testsupport.TestRuntimeConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.enginertugrul.iotsensormonitor.testsupport.TestRuntimeConfiguration.TEST_INSTANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;



@SpringBootTest(properties = "spring.config.location=classpath:/application-test.properties")
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class,TestRuntimeConfiguration.class})
@Execution(ExecutionMode.SAME_THREAD)
class AppUserServiceIT {

    private static final String RAW_PASSWORD = "  Registration-password-42!  ";

    @Autowired
    private AppUserService appUserService;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RegistrationProbe registrationProbe;

    private final List<String> fixtureEmails = new ArrayList<>();

    @BeforeEach
    void setUp() {
        registrationProbe.reset();
    }

    @AfterEach
    void tearDown() {
        registrationProbe.reset();
        for (String email : fixtureEmails) {
            jdbcTemplate.update("DELETE FROM app_users WHERE lower(email)=?",email);
        }
    }



    @Test
    void commitsNormalizedRegistrationEncodedPasswordPreferencesAndInitialChallenge() {
        String email = newEmail();
        RegisterUserForm form = registrationForm(" " + email.toUpperCase(Locale.ROOT) + " ");

        AppUser created = appUserService.createUser(form);
        AppUser reloaded = appUserRepository.findById(created.getId()).orElseThrow();

        assertThat(reloaded.getId()).isPositive();
        assertThat(reloaded.getEmail()).isEqualTo(email);
        assertThat(reloaded.getPasswordHash()).isNotEqualTo(RAW_PASSWORD);
        assertThat(passwordEncoder.matches(RAW_PASSWORD,reloaded.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(RAW_PASSWORD.trim(),reloaded.getPasswordHash())).isFalse();
        assertThat(reloaded.getPreferredLanguage()).isEqualTo(PreferredLanguage.TURKISH);
        assertThat(reloaded.getPreferredTemperatureUnit()).isEqualTo(TemperatureUnit.FAHRENHEIT);
        assertThat(reloaded.getPreferredTimezone()).isEqualTo("Europe/Istanbul");
        assertThat(reloaded.isEnabled()).isTrue();
        assertThat(reloaded.isEmailVerified()).isFalse();
        assertThat(reloaded.getEmailVerifiedAt()).isNull();
        assertThat(reloaded.getCreatedAt()).isEqualTo(TEST_INSTANT);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(TEST_INSTANT);
        assertThat(userCount(email)).isEqualTo(1);
        assertThat(challengeCount(reloaded.getId())).isEqualTo(1);
        assertThat(challengeHash(reloaded.getId())).isNotBlank();
        assertThat(registrationProbe.issuedUserId).isEqualTo(reloaded.getId());
    }



    @Test
    void rejectsKnownDuplicateWithoutChangingExistingAccountOrChallenge() {
        String email = newEmail();
        AppUser original = appUserService.createUser(registrationForm(email));
        String originalChallengeHash = challengeHash(original.getId());
        RegisterUserForm duplicate = registrationForm(" " + email.toUpperCase(Locale.ROOT) + " ");
        duplicate.setPassword("Different-password-73!");
        duplicate.setConfirmPassword("Different-password-73!");

        assertThatThrownBy(() -> appUserService.createUser(duplicate))
                .isInstanceOf(EmailAlreadyRegisteredException.class)
                .hasNoCause();

        AppUser reloaded = appUserRepository.findById(original.getId()).orElseThrow();

        assertThat(userCount(email)).isEqualTo(1);
        assertThat(reloaded.getPasswordHash()).isEqualTo(original.getPasswordHash());
        assertThat(reloaded.getUpdatedAt()).isEqualTo(original.getUpdatedAt());
        assertThat(challengeCount(original.getId())).isEqualTo(1);
        assertThat(challengeHash(original.getId())).isEqualTo(originalChallengeHash);
    }



    @Test
    void translatesActualPostgresEmailConstraintFailure() {
        String email = newEmail();
        Timestamp createdAt = Timestamp.from(TEST_INSTANT);
        Long originalId = jdbcTemplate.queryForObject(
                "INSERT INTO app_users (email,password_hash,created_at,updated_at) VALUES (?,?,?,?) RETURNING id",
                Long.class,email.toUpperCase(Locale.ROOT),"existing-password-hash",createdAt,createdAt);

        assertThat(appUserRepository.existsByEmail(email)).isFalse();

        assertThatThrownBy(() -> appUserService.createUser(registrationForm(email)))
                .isInstanceOf(EmailAlreadyRegisteredException.class)
                .hasCauseInstanceOf(DataIntegrityViolationException.class);

        AppUser original = appUserRepository.findById(originalId).orElseThrow();

        assertThat(userCount(email)).isEqualTo(1);
        assertThat(original.getEmail()).isEqualTo(email.toUpperCase(Locale.ROOT));
        assertThat(original.getPasswordHash()).isEqualTo("existing-password-hash");
        assertThat(challengeCount(originalId)).isZero();
    }



    @Test
    void rollsBackUserAndChallengeWhenInitialIssuanceFailsAfterBothAreSaved() {
        String email = newEmail();
        IllegalStateException failure = new IllegalStateException("Registration issuance failed");
        registrationProbe.afterIssuance = () -> {
            assertThat(userCount(email)).isEqualTo(1);
            assertThat(challengeCount(registrationProbe.issuedUserId)).isEqualTo(1);
            throw failure;
        };

        assertThatThrownBy(() -> appUserService.createUser(registrationForm(email))).isSameAs(failure);

        Long attemptedUserId = registrationProbe.issuedUserId;
        assertThat(attemptedUserId).isPositive();
        assertThat(userCount(email)).isZero();
        assertThat(appUserRepository.findById(attemptedUserId)).isEmpty();
        assertThat(challengeCount(attemptedUserId)).isZero();
    }



    @Test
    void commitsPreferenceChangesOnlyForTheSelectedUser() {
        Instant createdAt = TEST_INSTANT.minusSeconds(3600);
        Instant verifiedAt = TEST_INSTANT.minusSeconds(1800);
        AppUser original = new AppUser(newEmail(),"existing-password-hash",createdAt);
        original.verifyEmail(verifiedAt);
        original.disable(verifiedAt);
        appUserRepository.saveAndFlush(original);
        AppUser other = appUserRepository.saveAndFlush(new AppUser(newEmail(),"other-password-hash",createdAt));

        appUserService.updatePreferences(original.getId(),preferencesForm("Asia/Tokyo"));

        AppUser reloaded = appUserRepository.findById(original.getId()).orElseThrow();
        AppUser unchanged = appUserRepository.findById(other.getId()).orElseThrow();
        AccountSettingsPageDTO page = appUserService.getAccountSettingsPage(original.getId());

        assertThat(reloaded.getPreferredLanguage()).isEqualTo(PreferredLanguage.TURKISH);
        assertThat(reloaded.getPreferredTemperatureUnit()).isEqualTo(TemperatureUnit.KELVIN);
        assertThat(reloaded.getPreferredTimezone()).isEqualTo("Asia/Tokyo");
        assertThat(reloaded.getUpdatedAt()).isEqualTo(TEST_INSTANT);
        assertThat(reloaded.getCreatedAt()).isEqualTo(createdAt);
        assertThat(reloaded.getEmail()).isEqualTo(original.getEmail());
        assertThat(reloaded.getPasswordHash()).isEqualTo("existing-password-hash");
        assertThat(reloaded.getEmailVerifiedAt()).isEqualTo(verifiedAt);
        assertThat(reloaded.isEnabled()).isFalse();

        assertThat(unchanged.getPreferredLanguage()).isEqualTo(PreferredLanguage.ENGLISH);
        assertThat(unchanged.getPreferredTemperatureUnit()).isEqualTo(TemperatureUnit.CELSIUS);
        assertThat(unchanged.getPreferredTimezone()).isEqualTo("UTC");
        assertThat(unchanged.getUpdatedAt()).isEqualTo(createdAt);

        assertThat(appUserService.getPreferredTemperatureUnit(original.getId())).isEqualTo(TemperatureUnit.KELVIN);
        assertThat(appUserService.getPreferredTimezone(original.getId())).isEqualTo("Asia/Tokyo");
        assertThat(page.form().getPreferredLanguage()).isEqualTo(PreferredLanguage.TURKISH);
        assertThat(page.form().getTemperatureUnit()).isEqualTo(TemperatureUnit.KELVIN);
        assertThat(page.form().getPreferredTimezone()).isEqualTo("Asia/Tokyo");
        assertThat(page.emailVerified()).isTrue();
        assertThat(page.registeredAt()).isEqualTo(createdAt.atZone(ZoneId.of("Asia/Tokyo")));
    }



    @Test
    void rollsBackPartialPreferenceMutationWhenTimezoneIsInvalid() {
        Instant createdAt = TEST_INSTANT.minusSeconds(3600);
        AppUser original = appUserRepository.saveAndFlush(new AppUser(newEmail(),"existing-password-hash",createdAt));

        assertThatThrownBy(() -> appUserService.updatePreferences(original.getId(),preferencesForm("Invalid/Timezone")))
                .isInstanceOf(DateTimeException.class);

        AppUser reloaded = appUserRepository.findById(original.getId()).orElseThrow();

        assertThat(reloaded.getPreferredLanguage()).isEqualTo(PreferredLanguage.ENGLISH);
        assertThat(reloaded.getPreferredTemperatureUnit()).isEqualTo(TemperatureUnit.CELSIUS);
        assertThat(reloaded.getPreferredTimezone()).isEqualTo("UTC");
        assertThat(reloaded.getCreatedAt()).isEqualTo(createdAt);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(createdAt);
        assertThat(reloaded.getPasswordHash()).isEqualTo("existing-password-hash");
    }



    private String newEmail() {
        String email = "registration-" + UUID.randomUUID() + "@example.com";
        fixtureEmails.add(email);
        return email;
    }



    private long userCount(String email) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM app_users WHERE lower(email)=?",Long.class,email);
    }



    private long challengeCount(Long userId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM email_verification_challenges WHERE user_id=?",Long.class,userId);
    }



    private String challengeHash(Long userId) {
        return jdbcTemplate.queryForObject("SELECT code_hash FROM email_verification_challenges WHERE user_id=?",String.class,userId);
    }



    private static RegisterUserForm registrationForm(String email) {
        RegisterUserForm form = new RegisterUserForm();
        form.setEmail(email);
        form.setPassword(RAW_PASSWORD);
        form.setConfirmPassword(RAW_PASSWORD);
        form.setPreferredLanguage(PreferredLanguage.TURKISH);
        form.setPreferredTemperatureUnit(TemperatureUnit.FAHRENHEIT);
        form.setPreferredTimezone("Europe/Istanbul");
        return form;
    }



    private static UserPreferencesForm preferencesForm(String timezone) {
        UserPreferencesForm form = new UserPreferencesForm();
        form.setPreferredLanguage(PreferredLanguage.TURKISH);
        form.setTemperatureUnit(TemperatureUnit.KELVIN);
        form.setPreferredTimezone(timezone);
        return form;
    }



    @TestConfiguration(proxyBeanMethods = false)
    static class RegistrationTestConfiguration {

        @Bean
        RegistrationProbe registrationProbe() {
            return new RegistrationProbe();
        }
    }



    static class RegistrationProbe {

        private Long issuedUserId;
        private Runnable afterIssuance = () -> {};

        @EventListener
        public void onCodeIssued(EmailVerificationCodeDelivery delivery) {
            issuedUserId = delivery.userId();
            afterIssuance.run();
        }

        void reset() {
            issuedUserId = null;
            afterIssuance = () -> {};
        }
    }
}