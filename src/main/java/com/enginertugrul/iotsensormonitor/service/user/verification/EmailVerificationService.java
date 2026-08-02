package com.enginertugrul.iotsensormonitor.service.user.verification;


public interface EmailVerificationService {

    void issueInitialCode(Long userId);

    void requestNewCode(String email, String clientKey);

    EmailVerificationResult verifyCode(String email, String rawCode, String clientKey);
}