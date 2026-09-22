package com.enginertugrul.iotsensormonitor.entity.sensor;

import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.exception.SensorTimezoneLockedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.stream.Stream;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.UPDATED_AT;
import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.sensor;
import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class SensorTest {



    @Test
    void createsActiveSensorWithNormalizedDetailsAndNoReadingHistory() {
        AppUser owner = user();
        Sensor sensor = new Sensor(owner,SensorType.HUMIDITY," Room sensor "," Istanbul "," Kadikoy "," Window "," Europe/Istanbul ",CREATED_AT);

        assertThat(sensor.getId()).isNull();
        assertThat(sensor.getOwner()).isSameAs(owner);
        assertThat(sensor.getType()).isEqualTo(SensorType.HUMIDITY);
        assertThat(sensor.getName()).isEqualTo("Room sensor");
        assertThat(sensor.getCity()).isEqualTo("Istanbul");
        assertThat(sensor.getDistrict()).isEqualTo("Kadikoy");
        assertThat(sensor.getInstallationLocation()).isEqualTo("Window");
        assertThat(sensor.getTimezone()).isEqualTo("Europe/Istanbul");
        assertThat(sensor.isActive()).isTrue();
        assertThat(sensor.getIngestionTokenHash()).isNull();
        assertThat(sensor.getFirstReadingAt()).isNull();
        assertThat(sensor.hasRecordedReadings()).isFalse();
        assertThat(sensor.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(sensor.getUpdatedAt()).isEqualTo(CREATED_AT);
    }



    @ParameterizedTest
    @CsvSource({
            "TEMPERATURE,NUMERIC",
            "HUMIDITY,NUMERIC",
            "MOTION,BOOLEAN"
    })
    void exposesReadingValueKindForItsType(SensorType type,ReadingValueKind expected) {
        assertThat(sensor(type).getReadingValueKind()).isEqualTo(expected);
    }



    @Test
    void rejectsMissingOwnerTypeOrCreationTimestamp() {
        AppUser owner = user();

        assertThatNullPointerException()
                .isThrownBy(() -> new Sensor(null,SensorType.TEMPERATURE,"Room","Istanbul","Kadikoy","Window","UTC",CREATED_AT))
                .withMessage("owner must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> new Sensor(owner,null,"Room","Istanbul","Kadikoy","Window","UTC",CREATED_AT))
                .withMessage("type must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> new Sensor(owner,SensorType.TEMPERATURE,"Room","Istanbul","Kadikoy","Window","UTC",null))
                .withMessage("createdAt must not be null");
    }



    @ParameterizedTest
    @MethodSource("invalidDetails")
    void rejectsMissingOrBlankDetailsOnCreationAndUpdate(String name,String city,String district,String location,String fieldName) {
        Sensor sensor = sensor(SensorType.TEMPERATURE);
        AppUser owner = user();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Sensor(owner,SensorType.TEMPERATURE,name,city,district,location,"UTC",CREATED_AT))
                .withMessage(fieldName + " must not be blank");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> sensor.updateDetails(name,city,district,location,"UTC",UPDATED_AT))
                .withMessage(fieldName + " must not be blank");
    }



    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "," \t\n "})
    void rejectsMissingOrBlankTimezoneOnCreationAndUpdate(String timezone) {
        Sensor sensor = sensor(SensorType.TEMPERATURE);
        AppUser owner = user();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Sensor(owner,SensorType.TEMPERATURE,"Room","Istanbul","Kadikoy","Window",timezone,CREATED_AT))
                .withMessage("timezone must not be blank");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> sensor.updateDetails("Room","Istanbul","Kadikoy","Window",timezone,UPDATED_AT))
                .withMessage("timezone must not be blank");
    }



    @Test
    void rejectsUnknownTimezoneOnCreationAndUpdate() {
        Sensor sensor = sensor(SensorType.TEMPERATURE);
        AppUser owner = user();

        assertThatExceptionOfType(DateTimeException.class)
                .isThrownBy(() -> new Sensor(owner,SensorType.TEMPERATURE,"Room","Istanbul","Kadikoy","Window","Mars/Olympus_Mons",CREATED_AT));

        assertThatExceptionOfType(DateTimeException.class)
                .isThrownBy(() -> sensor.updateDetails("Room","Istanbul","Kadikoy","Window","Mars/Olympus_Mons",UPDATED_AT));
    }



    @Test
    void updatesDetailsAndTimezoneBeforeAnyReadings() {
        Sensor sensor = sensor(SensorType.TEMPERATURE);
        AppUser owner = sensor.getOwner();

        sensor.updateDetails(" Kitchen "," Ankara "," Cankaya "," Shelf "," Europe/Istanbul ",UPDATED_AT);

        assertThat(sensor.getName()).isEqualTo("Kitchen");
        assertThat(sensor.getCity()).isEqualTo("Ankara");
        assertThat(sensor.getDistrict()).isEqualTo("Cankaya");
        assertThat(sensor.getInstallationLocation()).isEqualTo("Shelf");
        assertThat(sensor.getTimezone()).isEqualTo("Europe/Istanbul");
        assertThat(sensor.getUpdatedAt()).isEqualTo(UPDATED_AT);
        assertThat(sensor.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(sensor.getOwner()).isSameAs(owner);
        assertThat(sensor.getType()).isEqualTo(SensorType.TEMPERATURE);
        assertThat(sensor.hasRecordedReadings()).isFalse();
    }



    @Test
    void allowsDetailUpdatesWithTheSameNormalizedTimezoneAfterReadings() {
        Sensor sensor = sensor(SensorType.TEMPERATURE);
        Instant recordedAt = CREATED_AT.plusSeconds(10);
        Instant detailsUpdatedAt = UPDATED_AT.plusSeconds(60);
        sensor.recordFirstReading(recordedAt,UPDATED_AT);

        sensor.updateDetails(" Kitchen "," Ankara "," Cankaya "," Shelf "," UTC ",detailsUpdatedAt);

        assertThat(sensor.getName()).isEqualTo("Kitchen");
        assertThat(sensor.getCity()).isEqualTo("Ankara");
        assertThat(sensor.getDistrict()).isEqualTo("Cankaya");
        assertThat(sensor.getInstallationLocation()).isEqualTo("Shelf");
        assertThat(sensor.getTimezone()).isEqualTo("UTC");
        assertThat(sensor.getFirstReadingAt()).isEqualTo(recordedAt);
        assertThat(sensor.getUpdatedAt()).isEqualTo(detailsUpdatedAt);
    }



    @ParameterizedTest
    @ValueSource(strings = {"Europe/Istanbul","Etc/UTC"})
    void rejectsTimezoneChangesAfterReadingsWithoutChangingDetails(String timezone) {
        Sensor sensor = sensor(SensorType.TEMPERATURE);
        Instant recordedAt = CREATED_AT.plusSeconds(10);
        sensor.recordFirstReading(recordedAt,UPDATED_AT);

        assertThatExceptionOfType(SensorTimezoneLockedException.class)
                .isThrownBy(() -> sensor.updateDetails("Kitchen","Ankara","Cankaya","Shelf",timezone,UPDATED_AT.plusSeconds(60)))
                .withMessage("Sensor timezone cannot be changed after readings have been recorded");

        assertThat(sensor.getName()).isEqualTo("Living room");
        assertThat(sensor.getCity()).isEqualTo("Istanbul");
        assertThat(sensor.getDistrict()).isEqualTo("Kadikoy");
        assertThat(sensor.getInstallationLocation()).isEqualTo("Window");
        assertThat(sensor.getTimezone()).isEqualTo("UTC");
        assertThat(sensor.getFirstReadingAt()).isEqualTo(recordedAt);
        assertThat(sensor.getUpdatedAt()).isEqualTo(UPDATED_AT);
    }



    @Test
    void activationChangesStateAndTimestampOnlyWhenStateChanges() {
        Sensor sensor = sensor(SensorType.TEMPERATURE);
        Instant reactivatedAt = UPDATED_AT.plusSeconds(60);

        sensor.activate(UPDATED_AT);

        assertThat(sensor.isActive()).isTrue();
        assertThat(sensor.getUpdatedAt()).isEqualTo(CREATED_AT);

        sensor.deactivate(UPDATED_AT);

        assertThat(sensor.isActive()).isFalse();
        assertThat(sensor.getUpdatedAt()).isEqualTo(UPDATED_AT);

        sensor.deactivate(UPDATED_AT.plusSeconds(30));

        assertThat(sensor.isActive()).isFalse();
        assertThat(sensor.getUpdatedAt()).isEqualTo(UPDATED_AT);

        sensor.activate(reactivatedAt);

        assertThat(sensor.isActive()).isTrue();
        assertThat(sensor.getUpdatedAt()).isEqualTo(reactivatedAt);

        sensor.activate(reactivatedAt.plusSeconds(30));

        assertThat(sensor.isActive()).isTrue();
        assertThat(sensor.getUpdatedAt()).isEqualTo(reactivatedAt);
        assertThat(sensor.getCreatedAt()).isEqualTo(CREATED_AT);
    }



    @Test
    void assignsAndReplacesIngestionTokenHash() {
        Sensor sensor = sensor(SensorType.TEMPERATURE);
        String firstHash = "a".repeat(64);
        String replacementHash = "b".repeat(64);
        Instant replacedAt = UPDATED_AT.plusSeconds(60);

        sensor.assignIngestionTokenHash(firstHash,UPDATED_AT);

        assertThat(sensor.getIngestionTokenHash()).isEqualTo(firstHash);
        assertThat(sensor.getUpdatedAt()).isEqualTo(UPDATED_AT);

        sensor.assignIngestionTokenHash(replacementHash,replacedAt);

        assertThat(sensor.getIngestionTokenHash()).isEqualTo(replacementHash);
        assertThat(sensor.getUpdatedAt()).isEqualTo(replacedAt);
        assertThat(sensor.getCreatedAt()).isEqualTo(CREATED_AT);
    }



    @Test
    void firstReadingCandidateCheckDoesNotMutateHistory() {
        Sensor sensor = sensor(SensorType.TEMPERATURE);

        assertThat(sensor.wouldUpdateFirstReading(CREATED_AT)).isTrue();
        assertThat(sensor.getFirstReadingAt()).isNull();
        assertThat(sensor.hasRecordedReadings()).isFalse();
        assertThat(sensor.getUpdatedAt()).isEqualTo(CREATED_AT);
    }



    @ParameterizedTest
    @ValueSource(longs = {0,1})
    void recordsFirstReadingAtOrAfterCreation(long elapsedSeconds) {
        Sensor sensor = sensor(SensorType.TEMPERATURE);
        Instant recordedAt = CREATED_AT.plusSeconds(elapsedSeconds);

        sensor.recordFirstReading(recordedAt,UPDATED_AT);

        assertThat(sensor.getFirstReadingAt()).isEqualTo(recordedAt);
        assertThat(sensor.hasRecordedReadings()).isTrue();
        assertThat(sensor.getUpdatedAt()).isEqualTo(UPDATED_AT);
        assertThat(sensor.getCreatedAt()).isEqualTo(CREATED_AT);
    }



    @Test
    void movesFirstReadingEarlierWhenAnOlderReadingArrives() {
        Sensor sensor = sensor(SensorType.TEMPERATURE);
        Instant originalRecordedAt = CREATED_AT.plusSeconds(30);
        Instant earlierRecordedAt = CREATED_AT.plusSeconds(10);
        Instant earlierAcceptedAt = UPDATED_AT.plusSeconds(60);
        sensor.recordFirstReading(originalRecordedAt,UPDATED_AT);

        assertThat(sensor.wouldUpdateFirstReading(earlierRecordedAt)).isTrue();

        sensor.recordFirstReading(earlierRecordedAt,earlierAcceptedAt);

        assertThat(sensor.getFirstReadingAt()).isEqualTo(earlierRecordedAt);
        assertThat(sensor.getUpdatedAt()).isEqualTo(earlierAcceptedAt);
        assertThat(sensor.hasRecordedReadings()).isTrue();
    }



    @ParameterizedTest
    @ValueSource(longs = {0,1})
    void equalOrLaterReadingsDoNotChangeFirstReadingOrUpdateTimestamp(long elapsedSeconds) {
        Sensor sensor = sensor(SensorType.TEMPERATURE);
        Instant firstRecordedAt = CREATED_AT.plusSeconds(10);
        Instant candidate = firstRecordedAt.plusSeconds(elapsedSeconds);
        sensor.recordFirstReading(firstRecordedAt,UPDATED_AT);

        assertThat(sensor.wouldUpdateFirstReading(candidate)).isFalse();

        sensor.recordFirstReading(candidate,UPDATED_AT.plusSeconds(60));

        assertThat(sensor.getFirstReadingAt()).isEqualTo(firstRecordedAt);
        assertThat(sensor.getUpdatedAt()).isEqualTo(UPDATED_AT);
    }



    @Test
    void rejectsMissingFirstReadingCandidate() {
        Sensor sensor = sensor(SensorType.TEMPERATURE);

        assertThatNullPointerException()
                .isThrownBy(() -> sensor.wouldUpdateFirstReading(null))
                .withMessage("candidate must not be null");
    }



    @Test
    void rejectsMissingFirstReadingTimestamps() {
        Sensor sensor = sensor(SensorType.TEMPERATURE);

        assertThatNullPointerException()
                .isThrownBy(() -> sensor.recordFirstReading(null,UPDATED_AT))
                .withMessage("recordedAt must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> sensor.recordFirstReading(CREATED_AT,null))
                .withMessage("updatedAt must not be null");

        assertThat(sensor.getFirstReadingAt()).isNull();
        assertThat(sensor.hasRecordedReadings()).isFalse();
        assertThat(sensor.getUpdatedAt()).isEqualTo(CREATED_AT);
    }



    @Test
    void rejectsFirstReadingBeforeSensorCreation() {
        Sensor sensor = sensor(SensorType.TEMPERATURE);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> sensor.recordFirstReading(CREATED_AT.minusNanos(1),UPDATED_AT))
                .withMessage("recordedAt must not be before sensor creation");

        assertThat(sensor.getFirstReadingAt()).isNull();
        assertThat(sensor.hasRecordedReadings()).isFalse();
        assertThat(sensor.getUpdatedAt()).isEqualTo(CREATED_AT);
    }



    @ParameterizedTest
    @ValueSource(longs = {0,1})
    void persistenceCallbacksAcceptUpdateAtOrAfterCreation(long elapsedSeconds) {
        Sensor sensor = sensor(SensorType.TEMPERATURE);
        sensor.deactivate(CREATED_AT.plusSeconds(elapsedSeconds));

        assertThatNoException().isThrownBy(sensor::prePersist);
        assertThatNoException().isThrownBy(sensor::preUpdate);
    }



    @ParameterizedTest
    @MethodSource("invalidUpdateTimestamps")
    void persistenceCallbacksRejectInvalidUpdateTimestamps(Instant updatedAt,String expectedMessage) {
        Sensor sensor = sensor(SensorType.TEMPERATURE);
        sensor.deactivate(updatedAt);

        assertThatIllegalStateException().isThrownBy(sensor::prePersist).withMessage(expectedMessage);
        assertThatIllegalStateException().isThrownBy(sensor::preUpdate).withMessage(expectedMessage);
    }



    @Test
    void persistenceCallbacksRejectUninitializedTimestamps() {
        Sensor sensor = new Sensor();

        assertThatIllegalStateException()
                .isThrownBy(sensor::prePersist)
                .withMessage("createdAt and updatedAt must not be null");

        assertThatIllegalStateException()
                .isThrownBy(sensor::preUpdate)
                .withMessage("createdAt and updatedAt must not be null");
    }



    private static Stream<Arguments> invalidDetails() {
        return Stream.of((String) null,""," \t ").flatMap(value -> Stream.of(
                Arguments.of(value,"Istanbul","Kadikoy","Window","name"),
                Arguments.of("Room",value,"Kadikoy","Window","city"),
                Arguments.of("Room","Istanbul",value,"Window","district"),
                Arguments.of("Room","Istanbul","Kadikoy",value,"installationLocation")
        ));
    }



    private static Stream<Arguments> invalidUpdateTimestamps() {
        return Stream.of(
                Arguments.of(null,"createdAt and updatedAt must not be null"),
                Arguments.of(CREATED_AT.minusNanos(1),"updatedAt must not be before createdAt")
        );
    }
}