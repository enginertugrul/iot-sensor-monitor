package com.enginertugrul.iotsensormonitor.entity.sensor;

import com.enginertugrul.iotsensormonitor.entity.DomainChecks;
import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;





@Getter
@Entity
@Table(name = "sensors")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Sensor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private AppUser owner;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 40)
    private SensorType type;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "city", nullable = false, length = 100)
    private String city;

    @Column(name = "district", nullable = false, length = 100)
    private String district;

    @Column(name = "home_location", nullable = false, length = 100)
    private String homeLocation;

    @Column(name = "time_zone_id", nullable = false, length = 64)
    private String timezone;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "ingestion_token_hash" , length = 64)
    private String ingestionTokenHash;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;





    public Sensor(
            AppUser owner,
            SensorType type,
            String name,
            String city,
            String district,
            String homeLocation,
            String timezone
    ) {
        this.owner = Objects.requireNonNull(owner, "owner must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.name = DomainChecks.requireText(name, "name");
        this.city = DomainChecks.requireText(city, "city");
        this.district = DomainChecks.requireText(district, "district");
        this.homeLocation = DomainChecks.requireText(homeLocation, "homeLocation");
        this.timezone = normalizeTimezone(timezone);

        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }





    public void updateDetails(
            String name,
            String city,
            String district,
            String homeLocation,
            String timezone
    ) {
        this.name = DomainChecks.requireText(name, "name");
        this.city = DomainChecks.requireText(city, "city");
        this.district = DomainChecks.requireText(district, "district");
        this.homeLocation = DomainChecks.requireText(homeLocation, "homeLocation");
        this.timezone = normalizeTimezone(timezone);
        this.updatedAt = Instant.now();
    }





    public void deactivate() {
        this.active = false;
        this.updatedAt = Instant.now();
    }




    public void activate() {
        this.active = true;
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
    }





    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }





    private static String normalizeTimezone(String value) {
        String timezone = DomainChecks.requireText(value, "timezone");
        return ZoneId.of(timezone).getId();
    }




    public void assignIngestionTokenHash(String ingestionTokenHash) {
        this.ingestionTokenHash = ingestionTokenHash;
        this.updatedAt = Instant.now();
    }




    public void markSeen(Instant seenAt) {
        this.lastSeenAt = seenAt;
    }


}