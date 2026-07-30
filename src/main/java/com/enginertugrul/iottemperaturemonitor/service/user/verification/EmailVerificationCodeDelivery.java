package com.enginertugrul.iottemperaturemonitor.service.user.verification;

import com.enginertugrul.iottemperaturemonitor.entity.user.AppUser;
import com.enginertugrul.iottemperaturemonitor.entity.user.PreferredLanguage;

import java.time.Instant;
import java.util.Objects;

public final class EmailVerificationCodeDelivery {

    private final Long userId;
    private final String recipientEmail;
    private final PreferredLanguage preferredLanguage;
    private final String rawCode;
    private final Instant expiresAt;

    public EmailVerificationCodeDelivery(
            Long userId,
            String recipientEmail,
            PreferredLanguage preferredLanguage,
            String rawCode,
            Instant expiresAt
    ) {
        if (userId == null || userId < 1) {
            throw new IllegalArgumentException("userId must be positive");
        }

        if (rawCode == null || !rawCode.matches("[0-9]{8}")) {
            throw new IllegalArgumentException("rawCode must contain exactly eight digits");
        }

        this.userId = userId;
        this.recipientEmail = AppUser.normalizeEmail(recipientEmail);
        this.preferredLanguage = Objects.requireNonNull(preferredLanguage, "preferredLanguage must not be null");
        this.rawCode = rawCode;
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    public Long userId() {
        return userId;
    }

    public String recipientEmail() {
        return recipientEmail;
    }

    public PreferredLanguage preferredLanguage() {
        return preferredLanguage;
    }

    public String rawCode() {
        return rawCode;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    @Override
    public String toString() {
        return "EmailVerificationCodeDelivery[userId=" + userId
                + ", recipientEmail=[REDACTED], rawCode=[REDACTED], expiresAt=" + expiresAt + "]";
    }
}