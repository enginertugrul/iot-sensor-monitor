package com.enginertugrul.iotsensormonitor.scheduler;

import com.enginertugrul.iotsensormonitor.repository.EmailVerificationChallengeRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;


@Component
public class EmailVerificationChallengeCleanupScheduler {

    private final EmailVerificationChallengeRepository emailVerificationChallengeRepository;
    private final Clock clock;



    public EmailVerificationChallengeCleanupScheduler(EmailVerificationChallengeRepository emailVerificationChallengeRepository, Clock clock) {
        this.emailVerificationChallengeRepository = emailVerificationChallengeRepository;
        this.clock = clock;
    }



    @Scheduled(fixedDelayString = "${app.security.email-verification.cleanup-interval:PT1H}")
    @Transactional
    public void purgeExpiredChallenges() {
        emailVerificationChallengeRepository.deleteExpiredAtOrBefore(clock.instant());
    }


}