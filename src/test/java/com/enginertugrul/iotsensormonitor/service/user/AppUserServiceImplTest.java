package com.enginertugrul.iotsensormonitor.service.user;

import com.enginertugrul.iotsensormonitor.dto.auth.RegisterUserForm;
import com.enginertugrul.iotsensormonitor.dto.user.AccountSettingsPageDTO;
import com.enginertugrul.iotsensormonitor.dto.user.UserPreferencesForm;
import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.entity.user.PreferredLanguage;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.exception.EmailAlreadyRegisteredException;
import com.enginertugrul.iotsensormonitor.repository.AppUserRepository;
import com.enginertugrul.iotsensormonitor.service.user.verification.EmailVerificationService;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import java.util.Optional;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.UPDATED_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;



@ExtendWith(MockitoExtension.class)
class AppUserServiceImplTest {

    private static final Long USER_ID = 42L;
    private static final String RAW_PASSWORD = "  Registration-password-42!  ";
    private static final String PASSWORD_HASH = "encoded-password-hash";

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private EmailVerificationService emailVerificationService;

    private AppUserServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AppUserServiceImpl(appUserRepository,passwordEncoder,emailVerificationService,Clock.fixed(UPDATED_AT,ZoneOffset.UTC));
    }



    @Test
    void registersNormalizedEmailWithEncodedPasswordAndRequestedPreferences() {
        RegisterUserForm form = registrationForm();
        form.setPreferredLanguage(PreferredLanguage.TURKISH);
        form.setPreferredTemperatureUnit(TemperatureUnit.FAHRENHEIT);
        form.setPreferredTimezone("Europe/Istanbul");
        stubSuccessfulRegistration();

        AppUser result = service.createUser(form);

        ArgumentCaptor<AppUser> userCaptor = ArgumentCaptor.forClass(AppUser.class);
        InOrder registrationOrder = inOrder(appUserRepository,emailVerificationService);
        registrationOrder.verify(appUserRepository).existsByEmail("owner@example.com");
        registrationOrder.verify(appUserRepository).saveAndFlush(userCaptor.capture());
        registrationOrder.verify(emailVerificationService).issueInitialCode(USER_ID);
        verify(passwordEncoder).encode(RAW_PASSWORD);

        assertThat(result).isSameAs(userCaptor.getValue());
        assertThat(result.getId()).isEqualTo(USER_ID);
        assertThat(result.getEmail()).isEqualTo("owner@example.com");
        assertThat(result.getPasswordHash()).isEqualTo(PASSWORD_HASH).isNotEqualTo(RAW_PASSWORD);
        assertThat(result.getPreferredLanguage()).isEqualTo(PreferredLanguage.TURKISH);
        assertThat(result.getPreferredTemperatureUnit()).isEqualTo(TemperatureUnit.FAHRENHEIT);
        assertThat(result.getPreferredTimezone()).isEqualTo("Europe/Istanbul");
        assertThat(result.isEnabled()).isTrue();
        assertThat(result.isEmailVerified()).isFalse();
        assertThat(result.getEmailVerifiedAt()).isNull();
        assertThat(result.getCreatedAt()).isEqualTo(UPDATED_AT);
        assertThat(result.getUpdatedAt()).isEqualTo(UPDATED_AT);
    }



    @Test
    void preservesDefaultRegistrationPreferences() {
        stubSuccessfulRegistration();

        AppUser result = service.createUser(registrationForm());

        assertThat(result.getPreferredLanguage()).isEqualTo(PreferredLanguage.ENGLISH);
        assertThat(result.getPreferredTemperatureUnit()).isEqualTo(TemperatureUnit.CELSIUS);
        assertThat(result.getPreferredTimezone()).isEqualTo("UTC");
        verify(emailVerificationService).issueInitialCode(USER_ID);
    }



    @Test
    void rejectsAnAlreadyRegisteredNormalizedEmailBeforeSaving() {
        when(appUserRepository.existsByEmail("owner@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.createUser(registrationForm()))
                .isInstanceOf(EmailAlreadyRegisteredException.class)
                .hasMessage("Email is already registered")
                .hasNoCause();

        verify(appUserRepository).existsByEmail("owner@example.com");
        verify(appUserRepository,never()).saveAndFlush(any(AppUser.class));
        verifyNoInteractions(emailVerificationService);
    }



    @Test
    void translatesNestedEmailUniqueConstraintViolationAndPreservesItsCause() {
        ConstraintViolationException constraint = new ConstraintViolationException("Duplicate email",new SQLException("duplicate key","23505"),"uk_app_users_email_lower");
        DataIntegrityViolationException failure = new DataIntegrityViolationException("Could not insert user",new IllegalStateException("Nested persistence failure",constraint));
        when(passwordEncoder.encode(RAW_PASSWORD)).thenReturn(PASSWORD_HASH);
        when(appUserRepository.saveAndFlush(any(AppUser.class))).thenThrow(failure);

        assertThatThrownBy(() -> service.createUser(registrationForm()))
                .isInstanceOf(EmailAlreadyRegisteredException.class)
                .hasMessage("Email is already registered")
                .hasCause(failure);

        verifyNoInteractions(emailVerificationService);
    }



    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"ck_app_users_password_hash_not_blank","uk_other_email"})
    void preservesConstraintFailuresThatDoNotIdentifyTheEmailUniqueIndex(String constraintName) {
        ConstraintViolationException constraint = new ConstraintViolationException("Constraint violation",new SQLException("constraint violation","23514"),constraintName);
        DataIntegrityViolationException failure = new DataIntegrityViolationException("uk_app_users_email_lower",constraint);
        when(passwordEncoder.encode(RAW_PASSWORD)).thenReturn(PASSWORD_HASH);
        when(appUserRepository.saveAndFlush(any(AppUser.class))).thenThrow(failure);

        assertThatThrownBy(() -> service.createUser(registrationForm())).isSameAs(failure);

        verifyNoInteractions(emailVerificationService);
    }



    @Test
    void preservesPersistenceFailureWithoutAConstraintCause() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException("Could not insert user");
        when(passwordEncoder.encode(RAW_PASSWORD)).thenReturn(PASSWORD_HASH);
        when(appUserRepository.saveAndFlush(any(AppUser.class))).thenThrow(failure);

        assertThatThrownBy(() -> service.createUser(registrationForm())).isSameAs(failure);

        verifyNoInteractions(emailVerificationService);
    }



    @Test
    void propagatesInitialChallengeFailure() {
        IllegalStateException failure = new IllegalStateException("Initial challenge failed");
        stubSuccessfulRegistration();
        doThrow(failure).when(emailVerificationService).issueInitialCode(USER_ID);

        assertThatThrownBy(() -> service.createUser(registrationForm())).isSameAs(failure);

        verify(appUserRepository).saveAndFlush(any(AppUser.class));
        verify(emailVerificationService).issueInitialCode(USER_ID);
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void mapsAccountSettingsUsingStoredPreferencesAndRegistrationTimezone(boolean verified) {
        AppUser user = new AppUser("owner@example.com",PASSWORD_HASH,PreferredLanguage.TURKISH,TemperatureUnit.FAHRENHEIT,"Europe/Istanbul",CREATED_AT);
        if (verified) {
            user.verifyEmail(UPDATED_AT);
        }
        when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        AccountSettingsPageDTO page = service.getAccountSettingsPage(USER_ID);

        assertThat(page.email()).isEqualTo("owner@example.com");
        assertThat(page.emailVerified()).isEqualTo(verified);
        assertThat(page.form().getPreferredLanguage()).isEqualTo(PreferredLanguage.TURKISH);
        assertThat(page.form().getTemperatureUnit()).isEqualTo(TemperatureUnit.FAHRENHEIT);
        assertThat(page.form().getPreferredTimezone()).isEqualTo("Europe/Istanbul");
        assertThat(page.registeredAt().toInstant()).isEqualTo(CREATED_AT);
        assertThat(page.registeredAt().getZone()).isEqualTo(ZoneId.of("Europe/Istanbul"));
        assertThat(page.registeredAt().getHour()).isEqualTo(15);

        page.form().setPreferredLanguage(PreferredLanguage.ENGLISH);

        assertThat(user.getPreferredLanguage()).isEqualTo(PreferredLanguage.TURKISH);
    }



    @Test
    void updatesPreferencesUsingTheClockWithoutChangingAccountIdentityOrSecurityState() {
        AppUser user = new AppUser("owner@example.com",PASSWORD_HASH,CREATED_AT);
        user.verifyEmail(CREATED_AT);
        user.disable(CREATED_AT);
        when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        service.updatePreferences(USER_ID,preferencesForm());

        assertThat(user.getPreferredLanguage()).isEqualTo(PreferredLanguage.TURKISH);
        assertThat(user.getPreferredTemperatureUnit()).isEqualTo(TemperatureUnit.FAHRENHEIT);
        assertThat(user.getPreferredTimezone()).isEqualTo("Europe/Istanbul");
        assertThat(user.getUpdatedAt()).isEqualTo(UPDATED_AT);
        assertThat(user.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(user.getEmail()).isEqualTo("owner@example.com");
        assertThat(user.getPasswordHash()).isEqualTo(PASSWORD_HASH);
        assertThat(user.getEmailVerifiedAt()).isEqualTo(CREATED_AT);
        assertThat(user.isEnabled()).isFalse();
        verifyNoInteractions(passwordEncoder,emailVerificationService);
    }



    @Test
    void returnsStoredTemperatureUnitAndTimezone() {
        AppUser user = new AppUser("owner@example.com",PASSWORD_HASH,PreferredLanguage.ENGLISH,TemperatureUnit.KELVIN,"Asia/Tokyo",CREATED_AT);
        when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        assertThat(service.getPreferredTemperatureUnit(USER_ID)).isEqualTo(TemperatureUnit.KELVIN);
        assertThat(service.getPreferredTimezone(USER_ID)).isEqualTo("Asia/Tokyo");
    }



    @Test
    void rejectsMissingUsersForAccountSettingsAndPreferenceOperations() {
        when(appUserRepository.findById(USER_ID)).thenReturn(Optional.empty());
        UserPreferencesForm form = preferencesForm();

        assertThatThrownBy(() -> service.getAccountSettingsPage(USER_ID))
                .isInstanceOf(NoSuchElementException.class).hasMessage("User not found");
        assertThatThrownBy(() -> service.updatePreferences(USER_ID,form))
                .isInstanceOf(NoSuchElementException.class).hasMessage("User not found");
        assertThatThrownBy(() -> service.getPreferredTemperatureUnit(USER_ID))
                .isInstanceOf(NoSuchElementException.class).hasMessage("User not found");
        assertThatThrownBy(() -> service.getPreferredTimezone(USER_ID))
                .isInstanceOf(NoSuchElementException.class).hasMessage("User not found");

        verifyNoInteractions(passwordEncoder,emailVerificationService);
    }



    private void stubSuccessfulRegistration() {
        when(passwordEncoder.encode(RAW_PASSWORD)).thenReturn(PASSWORD_HASH);
        when(appUserRepository.saveAndFlush(any(AppUser.class))).thenAnswer(invocation -> {
            AppUser user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user,"id",USER_ID);
            return user;
        });
    }



    private static RegisterUserForm registrationForm() {
        RegisterUserForm form = new RegisterUserForm();
        form.setEmail(" OWNER@Example.COM ");
        form.setPassword(RAW_PASSWORD);
        form.setConfirmPassword(RAW_PASSWORD);
        return form;
    }



    private static UserPreferencesForm preferencesForm() {
        UserPreferencesForm form = new UserPreferencesForm();
        form.setPreferredLanguage(PreferredLanguage.TURKISH);
        form.setTemperatureUnit(TemperatureUnit.FAHRENHEIT);
        form.setPreferredTimezone("Europe/Istanbul");
        return form;
    }
}