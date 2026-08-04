package com.enginertugrul.iotsensormonitor.dto.user;

import java.time.ZonedDateTime;

public record AccountSettingsPageDTO(UserPreferencesForm form,String email,boolean emailVerified,ZonedDateTime registeredAt) {
}