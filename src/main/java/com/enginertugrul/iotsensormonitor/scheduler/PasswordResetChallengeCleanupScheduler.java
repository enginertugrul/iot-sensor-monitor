package com.enginertugrul.iotsensormonitor.scheduler;

import com.enginertugrul.iotsensormonitor.repository.PasswordResetChallengeRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;


@Component
public class PasswordResetChallengeCleanupScheduler {



    private final PasswordResetChallengeRepository passwordResetChallengeRepository;
    private final Clock clock;



    public PasswordResetChallengeCleanupScheduler(PasswordResetChallengeRepository passwordResetChallengeRepository, Clock clock) {
        this.passwordResetChallengeRepository = passwordResetChallengeRepository;
        this.clock = clock;
    }




    @Scheduled(fixedDelayString ="${app.security.password-recovery.cleanup-interval:PT1H}")
    @Transactional
    public void purgeExpiredChallenges() {
        passwordResetChallengeRepository.deleteExpiredAtOrBefore(clock.instant());
    }



}