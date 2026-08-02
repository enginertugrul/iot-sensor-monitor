package com.enginertugrul.iotsensormonitor.service.user;

import com.enginertugrul.iotsensormonitor.dto.auth.RegisterUserForm;
import com.enginertugrul.iotsensormonitor.dto.user.UserPreferencesForm;
import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;

public interface AppUserService {

    AppUser createUser(RegisterUserForm registerUserForm);

    UserPreferencesForm getPreferences(Long userId);

    void updatePreferences(Long userId, UserPreferencesForm userPreferencesForm);

    TemperatureUnit getPreferredTemperatureUnit(Long userId);

}
