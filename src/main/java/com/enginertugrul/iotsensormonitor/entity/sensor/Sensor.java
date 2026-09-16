package com.enginertugrul.iotsensormonitor.entity.sensor;

import com.enginertugrul.iotsensormonitor.entity.DomainChecks;
import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.exception.SensorTimezoneLockedException;
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

    @Column(name = "installation_location", nullable = false, length = 100)
    private String installationLocation;

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

    @Column(name = "first_reading_at")
    private Instant firstReadingAt;





    public Sensor(
            AppUser owner,
            SensorType type,
            String name,
            String city,
            String district,
            String installationLocation,
            String timezone,
            Instant createdAt) {
        this.owner = Objects.requireNonNull(owner,"owner must not be null");
        this.type = Objects.requireNonNull(type,"type must not be null");
        this.name = DomainChecks.requireText(name,"name");
        this.city = DomainChecks.requireText(city,"city");
        this.district = DomainChecks.requireText(district,"district");
        this.installationLocation = DomainChecks.requireText(installationLocation,"installationLocation");
        this.timezone = normalizeTimezone(timezone);
        this.createdAt = Objects.requireNonNull(createdAt,"createdAt must not be null");
        this.updatedAt = this.createdAt;
    }





    public void updateDetails(String name,String city,String district,String installationLocation,String timezone,Instant updatedAt) {
        String normalizedTimezone = normalizeTimezone(timezone);

        if (hasRecordedReadings() && !this.timezone.equals(normalizedTimezone)) {
            throw new SensorTimezoneLockedException();
        }


        this.name = DomainChecks.requireText(name,"name");
        this.city = DomainChecks.requireText(city,"city");
        this.district = DomainChecks.requireText(district,"district");
        this.installationLocation = DomainChecks.requireText(installationLocation,"installationLocation");
        this.timezone = normalizedTimezone;
        this.updatedAt = updatedAt;
    }




    public ReadingValueKind getReadingValueKind() {
        return type.getReadingValueKind();
    }




    public void deactivate(Instant updatedAt) {
        if (active) {
            this.active = false;
            this.updatedAt = updatedAt;
        }
    }



    public void activate(Instant updatedAt) {
        if (!active) {
            this.active = true;
            this.updatedAt = updatedAt;
        }
    }







    public void assignIngestionTokenHash(String ingestionTokenHash,Instant updatedAt) {
        this.ingestionTokenHash = ingestionTokenHash;
        this.updatedAt = updatedAt;
    }





    public boolean wouldUpdateFirstReading(Instant candidate) {
        Objects.requireNonNull(candidate,"candidate must not be null");
        return firstReadingAt == null || candidate.isBefore(firstReadingAt);
    }




    public void recordFirstReading(Instant recordedAt,Instant updatedAt) {
        Objects.requireNonNull(recordedAt,"recordedAt must not be null");
        Objects.requireNonNull(updatedAt,"updatedAt must not be null");
        if (recordedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("recordedAt must not be before sensor creation");
        }
        if (wouldUpdateFirstReading(recordedAt)) {
            this.firstReadingAt = recordedAt;
            this.updatedAt = updatedAt;
        }
    }





    public boolean hasRecordedReadings() {
        return firstReadingAt != null;
    }




    @PrePersist
    void prePersist() {
        validateTimestamps();
    }



    @PreUpdate
    void preUpdate() {
        validateTimestamps();
    }




    private static String normalizeTimezone(String value) {
        String timezone = DomainChecks.requireText(value, "timezone");
        return ZoneId.of(timezone).getId();
    }



    private void validateTimestamps() {
        if (createdAt == null || updatedAt == null) {
            throw new IllegalStateException("createdAt and updatedAt must not be null");
        }

        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalStateException("updatedAt must not be before createdAt");
        }
    }


}