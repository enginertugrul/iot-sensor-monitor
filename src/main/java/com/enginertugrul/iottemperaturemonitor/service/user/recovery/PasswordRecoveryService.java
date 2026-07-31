package com.enginertugrul.iottemperaturemonitor.service.user.recovery;

import java.util.Optional;

public interface PasswordRecoveryService {

    Optional<PasswordRecoveryCodeDelivery> requestResetCode(String email,String clientKey);

    PasswordRecoveryResult resetPassword(String email,String rawCode,String newPassword,String clientKey);


}