package com.enginertugrul.iotsensormonitor.service.user;

import com.enginertugrul.iotsensormonitor.dto.auth.RegisterUserForm;
import com.enginertugrul.iotsensormonitor.dto.user.AccountSettingsPageDTO;
import com.enginertugrul.iotsensormonitor.dto.user.UserPreferencesForm;
import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.exception.EmailAlreadyRegisteredException;
import com.enginertugrul.iotsensormonitor.repository.AppUserRepository;
import com.enginertugrul.iotsensormonitor.service.user.verification.EmailVerificationService;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.NoSuchElementException;





@Service
public class AppUserServiceImpl implements AppUserService {

    private static final String APP_USER_EMAIL_UNIQUE_INDEX = "uk_app_users_email_lower";

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationService  emailVerificationService;


    public AppUserServiceImpl(AppUserRepository appUserRepository, PasswordEncoder passwordEncoder, EmailVerificationService emailVerificationService) {
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailVerificationService = emailVerificationService;
    }






    @Override
    @Transactional
    public AppUser createUser(RegisterUserForm registerUserForm) {


        String normalizedEmail = AppUser.normalizeEmail(registerUserForm.getEmail());

        String passwordHash = passwordEncoder.encode(registerUserForm.getPassword());

        ensureEmailIsAvailable(normalizedEmail);


        AppUser appUser = new AppUser(
                normalizedEmail,
                passwordHash,
                registerUserForm.getPreferredLanguage(),
                registerUserForm.getPreferredTemperatureUnit(),
                registerUserForm.getPreferredTimezone()
        );

        AppUser savedUser;

        try {
            savedUser = appUserRepository.saveAndFlush(appUser);
        } catch (DataIntegrityViolationException exception) {
            throw translateAppUserPersistenceException(exception);
        }

        emailVerificationService.issueInitialCode(savedUser.getId());
        return savedUser;

    }





    @Override
    @Transactional(readOnly = true)
    public AccountSettingsPageDTO getAccountSettingsPage(Long userId) {

        AppUser user = appUserRepository.findById(userId).orElseThrow(()-> new NoSuchElementException("User not found"));

        UserPreferencesForm form = new UserPreferencesForm();
        form.setPreferredLanguage(user.getPreferredLanguage());
        form.setTemperatureUnit(user.getPreferredTemperatureUnit());
        form.setPreferredTimezone(user.getPreferredTimezone());

        ZonedDateTime registeredAt = user.getCreatedAt().atZone(ZoneId.of(user.getPreferredTimezone()));

        return new AccountSettingsPageDTO(form, user.getEmail(), user.isEmailVerified(), registeredAt);
    }




    @Override
    @Transactional
    public void updatePreferences(Long userId, UserPreferencesForm userPreferencesForm) {

        AppUser user = appUserRepository.findById(userId).orElseThrow(()-> new NoSuchElementException("User not found"));

        user.updatePreferences(userPreferencesForm.getPreferredLanguage(), userPreferencesForm.getTemperatureUnit(), userPreferencesForm.getPreferredTimezone());

    }




    @Override
    @Transactional(readOnly = true)
    public TemperatureUnit getPreferredTemperatureUnit(Long userId) {
        return appUserRepository.findById(userId)
                .map(AppUser::getPreferredTemperatureUnit)
                .orElseThrow( ()-> new NoSuchElementException("User not found"));
    }





    @Transactional(readOnly = true)
    protected void ensureEmailIsAvailable(String normalizedEmail) {
        if (appUserRepository.existsByEmail(normalizedEmail)) {
            throw new EmailAlreadyRegisteredException();
        }
    }


    private RuntimeException translateAppUserPersistenceException(DataIntegrityViolationException exception) {

        Throwable cause = exception.getCause();

        while (cause != null) {
            if (cause instanceof ConstraintViolationException constraintViolation
                    && APP_USER_EMAIL_UNIQUE_INDEX.equals(constraintViolation.getConstraintName())) {

                return new EmailAlreadyRegisteredException(exception);
            }

            cause = cause.getCause();
        }

        return exception;
    }








}

