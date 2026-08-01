package com.enginertugrul.iottemperaturemonitor.scheduler;

import com.enginertugrul.iottemperaturemonitor.repository.PasswordResetChallengeRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;




@Component
public class PasswordResetChallengeCleanupScheduler {



    private final PasswordResetChallengeRepository passwordResetChallengeRepository;



    public PasswordResetChallengeCleanupScheduler(PasswordResetChallengeRepository passwordResetChallengeRepository) {
        this.passwordResetChallengeRepository = passwordResetChallengeRepository;
    }




    @Scheduled(fixedDelayString ="${app.security.password-recovery.cleanup-interval:PT1H}")
    @Transactional
    public void purgeExpiredChallenges() {
        passwordResetChallengeRepository.deleteExpiredAtOrBefore(Instant.now());
    }



}