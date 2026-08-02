package com.enginertugrul.iottemperaturemonitor.repository;

import com.enginertugrul.iottemperaturemonitor.entity.user.PasswordResetChallenge;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;



public interface PasswordResetChallengeRepository
        extends JpaRepository<PasswordResetChallenge,Long> {



    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT challenge
            FROM PasswordResetChallenge challenge
            WHERE challenge.user.id = :userId
            """)
    Optional<PasswordResetChallenge> findByUserIdForUpdate(@Param("userId") Long userId);



    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            DELETE FROM PasswordResetChallenge challenge
            WHERE challenge.expiresAt <= :cutoff
            """)
    void deleteExpiredAtOrBefore(@Param("cutoff") Instant cutoff);


}