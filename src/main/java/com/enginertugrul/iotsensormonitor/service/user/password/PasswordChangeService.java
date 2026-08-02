package com.enginertugrul.iotsensormonitor.service.user.password;

public interface PasswordChangeService {

    PasswordChangeResult changePassword(Long userId,String currentPassword,String newPassword);

}