package com.enginertugrul.iottemperaturemonitor.repository;

import com.enginertugrul.iottemperaturemonitor.entity.user.EmailVerificationChallenge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmailVerificationChallengeRepository extends JpaRepository<EmailVerificationChallenge,Long> {


    Optional<EmailVerificationChallenge> findByUserId(Long userId);

}
