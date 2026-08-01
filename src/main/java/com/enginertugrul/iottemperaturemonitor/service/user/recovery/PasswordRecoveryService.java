package com.enginertugrul.iottemperaturemonitor.service.user.recovery;


public interface PasswordRecoveryService {

    void requestResetCode(String email,String clientKey);

    PasswordRecoveryResult resetPassword(String email,String rawCode,String newPassword,String clientKey);


}