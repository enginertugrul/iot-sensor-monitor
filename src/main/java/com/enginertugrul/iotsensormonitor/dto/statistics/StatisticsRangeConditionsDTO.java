package com.enginertugrul.iotsensormonitor.dto.statistics;

public record StatisticsRangeConditionsDTO(
        boolean containsExpiredIntervals,
        boolean containsRollupDelayedIntervals,
        boolean containsIncompleteIntervals
) {

    public boolean fullyCovered() {
        return !containsExpiredIntervals
                && !containsRollupDelayedIntervals
                && !containsIncompleteIntervals;
    }
}