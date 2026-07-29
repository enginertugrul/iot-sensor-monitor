package com.enginertugrul.iottemperaturemonitor.entity.user;


import com.enginertugrul.iottemperaturemonitor.entity.DomainChecks;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

@Getter
@Entity
@Table(name = "email_verification_challenges")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmailVerificationChallenge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private AppUser user;

    @Column(name = "code_hash",  nullable = false, length = 255)
    private String codeHash;

    @Column(name = "issued_at",  nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "resend_available_at" , nullable = false)
    private Instant resendAvailableAt;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;



    public EmailVerificationChallenge(
            AppUser user,
            String codeHash,
            Instant issuedAt,
            Instant expiresAt,
            Instant resendAvailableAt
    ) {
        this.user = Objects.requireNonNull(user, "user must not be null");
        this.codeHash = DomainChecks.requireText(codeHash, "codeHash");
        this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        this.resendAvailableAt = Objects.requireNonNull(resendAvailableAt, "resendAvailableAt must not be null");
        this.failedAttempts = 0;
        this.createdAt = this.issuedAt;
        this.updatedAt = this.issuedAt;

        validateState();
    }


    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = issuedAt;
        }

        if (updatedAt == null) {
            updatedAt = createdAt;
        }

        validateState();
    }


    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
        validateState();
    }



    private void validateState() {
        Objects.requireNonNull(user, "user must not be null");
        codeHash = DomainChecks.requireText(codeHash, "codeHash");
        Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        Objects.requireNonNull(resendAvailableAt, "resendAvailableAt must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");

        if (failedAttempts < 0) {
            throw new IllegalStateException("failedAttempts must not be negative");
        }

        if (issuedAt.isBefore(createdAt)) {
            throw new IllegalStateException("issuedAt must not be before createdAt");
        }

        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalStateException("expiresAt must be after issuedAt");
        }

        if (resendAvailableAt.isBefore(issuedAt) || !resendAvailableAt.isBefore(expiresAt)) {
            throw new IllegalStateException("resendAvailableAt must be between issuedAt and expiresAt");
        }

        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalStateException("updatedAt must not be before createdAt");
        }
    }


}
