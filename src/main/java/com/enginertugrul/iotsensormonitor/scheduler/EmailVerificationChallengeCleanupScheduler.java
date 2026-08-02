package com.enginertugrul.iotsensormonitor.scheduler;

import com.enginertugrul.iotsensormonitor.repository.EmailVerificationChallengeRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;




@Component
public class EmailVerificationChallengeCleanupScheduler {

    private final EmailVerificationChallengeRepository emailVerificationChallengeRepository;



    public EmailVerificationChallengeCleanupScheduler(EmailVerificationChallengeRepository emailVerificationChallengeRepository) {
        this.emailVerificationChallengeRepository = emailVerificationChallengeRepository;
    }



    @Scheduled(fixedDelayString = "${app.security.email-verification.cleanup-interval:PT1H}")
    @Transactional
    public void purgeExpiredChallenges() {
        emailVerificationChallengeRepository.deleteExpiredAtOrBefore(Instant.now());
    }


}