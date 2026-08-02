package com.enginertugrul.iotsensormonitor.service.user.recovery;

import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.entity.user.PasswordResetChallenge;
import com.enginertugrul.iotsensormonitor.repository.AppUserRepository;
import com.enginertugrul.iotsensormonitor.repository.PasswordResetChallengeRepository;
import com.enginertugrul.iotsensormonitor.security.onetimecode.GeneratedOneTimeCode;
import com.enginertugrul.iotsensormonitor.security.recovery.PasswordRecoveryCodeGenerator;
import com.enginertugrul.iotsensormonitor.security.recovery.PasswordRecoveryPolicy;
import com.enginertugrul.iotsensormonitor.security.recovery.PasswordRecoveryRateLimiter;
import com.enginertugrul.iotsensormonitor.service.user.password.PasswordChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;



@Service
public class PasswordRecoveryServiceImpl implements PasswordRecoveryService {


    private static final int MAXIMUM_EMAIL_LENGTH = 320;
    private static final int MINIMUM_PASSWORD_LENGTH = 8;
    private static final int MAXIMUM_PASSWORD_LENGTH = 72;
    private static final String INVALID_ADDRESS_RATE_LIMIT_KEY = "[invalid-address]";


    private final AppUserRepository appUserRepository;
    private final PasswordResetChallengeRepository passwordResetChallengeRepository;
    private final PasswordRecoveryCodeGenerator passwordRecoveryCodeGenerator;
    private final PasswordRecoveryPolicy policy;
    private final PasswordRecoveryRateLimiter rateLimiter;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;




    public PasswordRecoveryServiceImpl(
            AppUserRepository appUserRepository,
            PasswordResetChallengeRepository passwordResetChallengeRepository,
            PasswordRecoveryCodeGenerator passwordRecoveryCodeGenerator,
            PasswordRecoveryPolicy policy,
            PasswordRecoveryRateLimiter rateLimiter,
            PasswordEncoder passwordEncoder,
            ApplicationEventPublisher eventPublisher
    ) {
        this.appUserRepository = appUserRepository;
        this.passwordResetChallengeRepository = passwordResetChallengeRepository;
        this.passwordRecoveryCodeGenerator = passwordRecoveryCodeGenerator;
        this.policy = policy;
        this.rateLimiter = rateLimiter;
        this.passwordEncoder = passwordEncoder;
        this.eventPublisher = eventPublisher;
    }




    @Override
    @Transactional
    public void requestResetCode(String email,String clientKey) {

        Instant requestedAt = Instant.now();
        String normalizedEmail = normalizeEmailOrNull(email);

        String addressRateLimitKey = normalizedEmail == null
                ? INVALID_ADDRESS_RATE_LIMIT_KEY
                : normalizedEmail;

        boolean requestAllowed = rateLimiter.allowCodeIssue(addressRateLimitKey, clientKey, requestedAt);

        if (!requestAllowed || normalizedEmail == null) {
            return;
        }

        Optional<AppUser> userResult = appUserRepository.findByEmailForUpdate(normalizedEmail);

        if (userResult.isEmpty()) {
            return;
        }

        AppUser user = userResult.get();

        Optional<PasswordResetChallenge> existingChallenge = passwordResetChallengeRepository.findByUserIdForUpdate(user.getId());

        if (!user.isEnabled() || !user.isEmailVerified()) {
            existingChallenge.ifPresent(passwordResetChallengeRepository::delete);
            return;
        }

        if (existingChallenge.isPresent() && !existingChallenge.get().isResendAvailableAt(requestedAt)) {
            return;
        }

        issueCode(user,existingChallenge.orElse(null),requestedAt);


    }






    @Override
    @Transactional
    public PasswordRecoveryResult resetPassword(String email, String rawCode, String newPassword, String clientKey) {

        Instant attemptedAt = Instant.now();

        if (!rateLimiter.allowPasswordReset(clientKey,attemptedAt)) {
            return PasswordRecoveryResult.INVALID;
        }

        if (!isValidNewPassword(newPassword)) {
            return PasswordRecoveryResult.INVALID;
        }

        String normalizedEmail = normalizeEmailOrNull(email);

        if (normalizedEmail == null) {
            return PasswordRecoveryResult.INVALID;
        }

        Optional<AppUser> userResult = appUserRepository.findByEmailForUpdate(normalizedEmail);

        if (userResult.isEmpty()) {
            return PasswordRecoveryResult.INVALID;
        }

        AppUser user = userResult.get();

        Optional<PasswordResetChallenge> challengeResult = passwordResetChallengeRepository.findByUserIdForUpdate(user.getId());

        if (!user.isEnabled() || !user.isEmailVerified()) {
            challengeResult.ifPresent(passwordResetChallengeRepository::delete);
            return PasswordRecoveryResult.INVALID;
        }

        if (challengeResult.isEmpty()) {
            return PasswordRecoveryResult.INVALID;
        }

        PasswordResetChallenge challenge = challengeResult.get();

        if ( challenge.isExpiredAt(attemptedAt) || challenge.hasReachedAttemptLimit(policy.getMaximumFailedAttempts()) ) {
            return PasswordRecoveryResult.INVALID;
        }

        if (!passwordRecoveryCodeGenerator.matches(user.getId(), rawCode, challenge.getCodeHash())) {
            challenge.recordFailedAttempt(attemptedAt);
            return PasswordRecoveryResult.INVALID;
        }

        String passwordHash = passwordEncoder.encode(newPassword);
        user.updatePasswordHash(passwordHash);
        passwordResetChallengeRepository.delete(challenge);

        eventPublisher.publishEvent(new PasswordChangedEvent(user.getId()));

        return PasswordRecoveryResult.PASSWORD_RESET;
    }







    private void issueCode(AppUser user, PasswordResetChallenge existingChallenge, Instant issuedAt) {

        GeneratedOneTimeCode generatedCode = passwordRecoveryCodeGenerator.generate(user.getId());

        Instant expiresAt = issuedAt.plus(policy.getCodeLifetime());
        Instant resendAvailableAt = issuedAt.plus(policy.getResendCooldown());


        if (existingChallenge == null) {

            passwordResetChallengeRepository.save(
                    new PasswordResetChallenge(
                            user,
                            generatedCode.codeHash(),
                            issuedAt,
                            expiresAt,
                            resendAvailableAt
                    )
            );

        } else {

            existingChallenge.rotateCode(
                    generatedCode.codeHash(),
                    issuedAt,
                    expiresAt,
                    resendAvailableAt
            );

        }


        PasswordRecoveryCodeDelivery delivery = new PasswordRecoveryCodeDelivery(
                user.getId(),
                user.getEmail(),
                user.getPreferredLanguage(),
                generatedCode.rawCode(),
                expiresAt
        );

        eventPublisher.publishEvent(delivery);

    }




    private String normalizeEmailOrNull(String email) {
        if ( email == null || email.length() > MAXIMUM_EMAIL_LENGTH) {
            return null;
        }

        try {
            return AppUser.normalizeEmail(email);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }




    private boolean isValidNewPassword(String newPassword) {
        return newPassword != null
                && !newPassword.isBlank()
                && newPassword.length() >= MINIMUM_PASSWORD_LENGTH
                && newPassword.length() <= MAXIMUM_PASSWORD_LENGTH;
    }




}