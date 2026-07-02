package com.enginertugrul.iottemperaturemonitor.service.user;

import com.enginertugrul.iottemperaturemonitor.dto.auth.RegisterUserForm;
import com.enginertugrul.iottemperaturemonitor.dto.user.UserPreferencesForm;
import com.enginertugrul.iottemperaturemonitor.entity.user.AppUser;

public interface AppUserService {

    AppUser createUser(RegisterUserForm registerUserForm);

    UserPreferencesForm getPreferences(Long userId);

    void updatePreferences(Long userId, UserPreferencesForm userPreferencesForm);

}
