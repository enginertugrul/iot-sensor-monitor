package com.enginertugrul.iottemperaturemonitor.service.user.verification;

import java.util.Optional;

public interface EmailVerificationService {

    void issueInitialCode(Long userId);

    void requestNewCode(String email, String clientKey);

    EmailVerificationResult verifyCode(String email, String rawCode, String clientKey);
}