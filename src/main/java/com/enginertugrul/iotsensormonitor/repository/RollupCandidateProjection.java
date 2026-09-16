package com.enginertugrul.iotsensormonitor.repository;


import java.time.Instant;

public interface RollupCandidateProjection {

    Long getId();
    String getTimezone();
    Instant getFirstReadingAt();
    Instant getHourlyCoveredUntil();
    Instant getDailyCoveredUntil();
}
