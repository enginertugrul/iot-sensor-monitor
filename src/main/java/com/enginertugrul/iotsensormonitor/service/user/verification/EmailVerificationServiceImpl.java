package com.enginertugrul.iotsensormonitor.service.user.verification;

import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.entity.user.EmailVerificationChallenge;
import com.enginertugrul.iotsensormonitor.exception.EmailVerificationUserNotFoundException;
import com.enginertugrul.iotsensormonitor.repository.AppUserRepository;
import com.enginertugrul.iotsensormonitor.repository.EmailVerificationChallengeRepository;
import com.enginertugrul.iotsensormonitor.security.onetimecode.GeneratedOneTimeCode;
import com.enginertugrul.iotsensormonitor.security.verification.EmailVerificationCodeGenerator;
import com.enginertugrul.iotsensormonitor.security.verification.EmailVerificationPolicy;
import com.enginertugrul.iotsensormonitor.security.verification.EmailVerificationRateLimiter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;





@Service
public class EmailVerificationServiceImpl implements EmailVerificationService {

    private static final String INVALID_ADDRESS_RATE_LIMIT_KEY = "[invalid-address]";

    private final AppUserRepository appUserRepository;
    private final EmailVerificationChallengeRepository emailVerificationChallengeRepository;
    private final EmailVerificationCodeGenerator emailVerificationCodeGenerator;
    private final EmailVerificationPolicy policy;
    private final EmailVerificationRateLimiter rateLimiter;
    private final ApplicationEventPublisher eventPublisher;



    public EmailVerificationServiceImpl(AppUserRepository appUserRepository, EmailVerificationChallengeRepository emailVerificationChallengeRepository, EmailVerificationCodeGenerator emailVerificationCodeGenerator, EmailVerificationPolicy policy, EmailVerificationRateLimiter rateLimiter, ApplicationEventPublisher eventPublisher) {
        this.appUserRepository = appUserRepository;
        this.emailVerificationChallengeRepository = emailVerificationChallengeRepository;
        this.emailVerificationCodeGenerator = emailVerificationCodeGenerator;
        this.policy = policy;
        this.rateLimiter = rateLimiter;
        this.eventPublisher = eventPublisher;
    }



    @Override
    @Transactional
    public void issueInitialCode(Long userId) {

        Long requiredUserId = requireUserId(userId);

        AppUser user = appUserRepository.findByIdForUpdate(requiredUserId)
                .orElseThrow(EmailVerificationUserNotFoundException::new);

        Optional<EmailVerificationChallenge> existingChallenge =
                emailVerificationChallengeRepository.findByUserIdForUpdate(requiredUserId);

        if (!user.isEnabled() || user.isEmailVerified()) {
            throw new IllegalStateException("Initial verification code cannot be issued for this user");
        }

        if (existingChallenge.isPresent()) {
            throw new IllegalStateException("Initial email verification challenge already exists");
        }

        issueCode(user,null,Instant.now());
    }



    @Override
    @Transactional
    public void requestNewCode(String email, String clientKey) {

        Instant requestedAt = Instant.now();
        String normalizedEmail = normalizeEmailOrNull(email);
        String addressRateLimitKey = normalizedEmail == null
                ? INVALID_ADDRESS_RATE_LIMIT_KEY
                : normalizedEmail;

        boolean requestAllowed = rateLimiter.allowCodeIssue(addressRateLimitKey,clientKey,requestedAt);

        if (!requestAllowed || normalizedEmail == null) {
            return;
        }

        Optional<AppUser> userResult = appUserRepository.findByEmailForUpdate(normalizedEmail);

        if (userResult.isEmpty()) {
            return;
        }

        AppUser user = userResult.get();
        Optional<EmailVerificationChallenge> existingChallenge =
                emailVerificationChallengeRepository.findByUserIdForUpdate(user.getId());

        if (user.isEmailVerified()) {
            existingChallenge.ifPresent(emailVerificationChallengeRepository::delete);
            return;
        }

        if (!user.isEnabled()) {
            return;
        }

        if (existingChallenge.isPresent() && !existingChallenge.get().isResendAvailableAt(requestedAt)) {
            return;
        }

        issueCode(user,existingChallenge.orElse(null),requestedAt);

    }




    @Override
    @Transactional
    public EmailVerificationResult verifyCode(String email, String rawCode, String clientKey) {

        Instant attemptedAt = Instant.now();

        if (!rateLimiter.allowVerification(clientKey,attemptedAt)) {
            return EmailVerificationResult.INVALID;
        }

        String normalizedEmail = normalizeEmailOrNull(email);

        if (normalizedEmail == null) {
            return EmailVerificationResult.INVALID;
        }

        Optional<AppUser> userResult = appUserRepository.findByEmailForUpdate(normalizedEmail);

        if (userResult.isEmpty()) {
            return EmailVerificationResult.INVALID;
        }

        AppUser user = userResult.get();
        Optional<EmailVerificationChallenge> challengeResult =
                emailVerificationChallengeRepository.findByUserIdForUpdate(user.getId());

        if (user.isEmailVerified()) {
            challengeResult.ifPresent(emailVerificationChallengeRepository::delete);
            return EmailVerificationResult.INVALID;
        }

        if (!user.isEnabled() || challengeResult.isEmpty()) {
            return EmailVerificationResult.INVALID;
        }

        EmailVerificationChallenge challenge = challengeResult.get();

        if (challenge.isExpiredAt(attemptedAt)
                || challenge.hasReachedAttemptLimit(policy.getMaximumFailedAttempts())) {
            return EmailVerificationResult.INVALID;
        }

        if (!emailVerificationCodeGenerator.matches(user.getId(),rawCode,challenge.getCodeHash())) {
            challenge.recordFailedAttempt(attemptedAt);
            return EmailVerificationResult.INVALID;
        }

        user.verifyEmail(attemptedAt);
        emailVerificationChallengeRepository.delete(challenge);
        return EmailVerificationResult.VERIFIED;
    }






    private void issueCode(AppUser user, EmailVerificationChallenge existingChallenge,Instant issuedAt) {

        GeneratedOneTimeCode generatedCode = emailVerificationCodeGenerator.generate(user.getId());

        Instant expiresAt = issuedAt.plus(policy.getCodeLifetime());
        Instant resendAvailableAt = issuedAt.plus(policy.getResendCooldown());

        if (existingChallenge == null) {
            emailVerificationChallengeRepository.save(new EmailVerificationChallenge(
                    user,
                    generatedCode.codeHash(),
                    issuedAt,
                    expiresAt,
                    resendAvailableAt
            ));
        } else {
            existingChallenge.rotateCode(
                    generatedCode.codeHash(),
                    issuedAt,
                    expiresAt,
                    resendAvailableAt
            );
        }

        EmailVerificationCodeDelivery delivery = new EmailVerificationCodeDelivery(
                user.getId(),
                user.getEmail(),
                user.getPreferredLanguage(),
                generatedCode.rawCode(),
                expiresAt
        );

        eventPublisher.publishEvent(delivery);

    }




    private Long requireUserId(Long userId) {
        if (userId == null || userId < 1) {
            throw new IllegalArgumentException("userId must be positive");
        }

        return userId;
    }




    private String normalizeEmailOrNull(String email) {
        try {
            return AppUser.normalizeEmail(email);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }



}