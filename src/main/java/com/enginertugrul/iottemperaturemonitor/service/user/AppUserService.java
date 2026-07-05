package com.enginertugrul.iottemperaturemonitor.service.user;

import com.enginertugrul.iottemperaturemonitor.dto.auth.RegisterUserForm;
import com.enginertugrul.iottemperaturemonitor.dto.user.UserPreferencesForm;
import com.enginertugrul.iottemperaturemonitor.entity.user.AppUser;
import com.enginertugrul.iottemperaturemonitor.entity.user.TemperatureUnit;

public interface AppUserService {

    AppUser createUser(RegisterUserForm registerUserForm);

    UserPreferencesForm getPreferences(Long userId);

    void updatePreferences(Long userId, UserPreferencesForm userPreferencesForm);

    TemperatureUnit getPreferredTemperatureUnit(Long userId);

}
