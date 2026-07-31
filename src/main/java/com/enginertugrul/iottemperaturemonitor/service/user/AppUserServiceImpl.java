package com.enginertugrul.iottemperaturemonitor.service.user;

import com.enginertugrul.iottemperaturemonitor.dto.auth.RegisterUserForm;
import com.enginertugrul.iottemperaturemonitor.dto.user.UserPreferencesForm;
import com.enginertugrul.iottemperaturemonitor.entity.user.AppUser;
import com.enginertugrul.iottemperaturemonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iottemperaturemonitor.repository.AppUserRepository;
import com.enginertugrul.iottemperaturemonitor.service.user.verification.EmailVerificationService;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;

@Service
public class AppUserServiceImpl implements AppUserService {

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

        AppUser savedUser =  appUserRepository.saveAndFlush(appUser);
        emailVerificationService.issueInitialCode(savedUser.getId());
        return savedUser;

    }

    @Override
    @Transactional(readOnly = true)
    public UserPreferencesForm getPreferences(Long userId) {

        AppUser user = appUserRepository.findById(userId).orElseThrow(()-> new NoSuchElementException("User not found"));

        UserPreferencesForm form = new UserPreferencesForm();
        form.setPreferredLanguage(user.getPreferredLanguage());
        form.setTemperatureUnit(user.getPreferredTemperatureUnit());
        form.setPreferredTimezone(user.getPreferredTimezone());

        return form;
    }


    @Override
    @Transactional
    @Modifying
    public void updatePreferences(Long userId, UserPreferencesForm userPreferencesForm) {

        AppUser user = appUserRepository.findById(userId).orElseThrow(()-> new NoSuchElementException("User not found"));

        user.updatePreferences(userPreferencesForm.getPreferredLanguage()
        , userPreferencesForm.getTemperatureUnit()
        , userPreferencesForm.getPreferredTimezone()
        );

    }

    @Transactional(readOnly = true)
    protected void ensureEmailIsAvailable(String normalizedEmail) {
        if (appUserRepository.existsByEmail(normalizedEmail)) {
            throw new IllegalArgumentException("Email is already registered");
        }
    }


    @Override
    @Transactional(readOnly = true)
    public TemperatureUnit getPreferredTemperatureUnit(Long userId) {
        return appUserRepository.findById(userId)
                .map(AppUser::getPreferredTemperatureUnit)
                .orElseThrow( ()-> new NoSuchElementException("User not found"));
    }
}

