package com.enginertugrul.iottemperaturemonitor.entity.user;

import com.enginertugrul.iottemperaturemonitor.entity.DomainChecks;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Objects;






@Getter
@Entity
@Table(name = "app_users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppUser {

    private static final PreferredLanguage DEFAULT_PREFERRED_LANGUAGE = PreferredLanguage.ENGLISH;
    private static final TemperatureUnit DEFAULT_PREFERRED_TEMPERATURE_UNIT = TemperatureUnit.CELSIUS;
    private static final String DEFAULT_PREFERRED_TIMEZONE = "UTC";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "preferred_language", nullable = false, length = 30)
    private PreferredLanguage preferredLanguage = DEFAULT_PREFERRED_LANGUAGE;

    @Enumerated(EnumType.STRING)
    @Column(name = "preferred_temperature_unit", nullable = false, length = 30)
    private TemperatureUnit preferredTemperatureUnit = DEFAULT_PREFERRED_TEMPERATURE_UNIT;

    @Column(name = "preferred_timezone", nullable = false, length = 64)
    private String preferredTimezone = DEFAULT_PREFERRED_TIMEZONE;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;






    public AppUser(String email, String passwordHash) {
        this(
                email,
                passwordHash,
                DEFAULT_PREFERRED_LANGUAGE,
                DEFAULT_PREFERRED_TEMPERATURE_UNIT,
                DEFAULT_PREFERRED_TIMEZONE
        );
    }



    public AppUser(
            String email,
            String passwordHash,
            PreferredLanguage preferredLanguage,
            TemperatureUnit preferredTemperatureUnit,
            String preferredTimezone
    ) {
        this.email = normalizeEmail(email);
        this.passwordHash = DomainChecks.requireText(passwordHash, "passwordHash");
        this.preferredLanguage = Objects.requireNonNullElse(preferredLanguage, DEFAULT_PREFERRED_LANGUAGE);
        this.preferredTemperatureUnit = Objects.requireNonNullElse(
                preferredTemperatureUnit,
                DEFAULT_PREFERRED_TEMPERATURE_UNIT
        );
        this.preferredTimezone = normalizeTimezone(Objects.requireNonNullElse(preferredTimezone, DEFAULT_PREFERRED_TIMEZONE) );

        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }





    public void updatePreferences(PreferredLanguage preferredLanguage,TemperatureUnit preferredTemperatureUnit,String preferredTimezone) {

        this.preferredLanguage = Objects.requireNonNullElse(preferredLanguage, DEFAULT_PREFERRED_LANGUAGE);
        this.preferredTemperatureUnit = Objects.requireNonNullElse(preferredTemperatureUnit,DEFAULT_PREFERRED_TEMPERATURE_UNIT);
        this.preferredTimezone = normalizeTimezone(Objects.requireNonNullElse(preferredTimezone, DEFAULT_PREFERRED_TIMEZONE));
        this.updatedAt = Instant.now();
    }



    public boolean isEmailVerified() {
        return this.emailVerifiedAt != null;
    }





    public void verifyEmail(Instant verifiedAt) {
        Instant requiredVerifiedAt = Objects.requireNonNull(verifiedAt, "verifiedAt must not be null");

        if (emailVerifiedAt != null) {
            return;
        }

        if (createdAt == null) {
            throw new IllegalStateException("createdAt must not be null");
        }

        if (requiredVerifiedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("verifiedAt must not be before createdAt");
        }

        this.emailVerifiedAt = requiredVerifiedAt;
        this.updatedAt = requiredVerifiedAt;
    }




    public void updatePasswordHash(String passwordHash) {
        this.passwordHash = DomainChecks.requireText(passwordHash, "passwordHash");
        this.updatedAt = Instant.now();
    }




    public void disable() {
        this.enabled = false;
        this.updatedAt = Instant.now();
    }



    public void enable() {
        this.enabled = true;
        this.updatedAt = Instant.now();
    }



    @PrePersist
    void prePersist() {
        Instant now = Instant.now();

        if (createdAt == null) {
            createdAt = now;
        }

        if (updatedAt == null) {
            updatedAt = now;
        }

        if (preferredLanguage == null) {
            preferredLanguage = DEFAULT_PREFERRED_LANGUAGE;
        }

        if (preferredTemperatureUnit == null) {
            preferredTemperatureUnit = DEFAULT_PREFERRED_TEMPERATURE_UNIT;
        }

        if (preferredTimezone == null || preferredTimezone.isBlank()) {
            preferredTimezone = DEFAULT_PREFERRED_TIMEZONE;
        }
    }



    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }




    public static String normalizeEmail(String value) {
        return DomainChecks.requireText(value, "email").toLowerCase(Locale.ROOT);
    }




    private static String normalizeTimezone(String value) {
        String timezone = DomainChecks.requireText(value, "preferredTimezone");
        return ZoneId.of(timezone).getId();
    }


}
