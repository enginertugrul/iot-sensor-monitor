package com.enginertugrul.iottemperaturemonitor.entity.alert;

import com.enginertugrul.iottemperaturemonitor.entity.DomainChecks;




public final class AlertCooldownPolicy {

    public static final int DEFAULT_MINUTES = 60;
    public static final int MIN_MINUTES = 1;
    public static final int MAX_MINUTES = 10080;

    private AlertCooldownPolicy() {
    }

    public static int requireValid(Integer cooldownMinutes) {
        return DomainChecks.requireIntegerBetween(cooldownMinutes,MIN_MINUTES,MAX_MINUTES, "cooldownMinutes");
    }
}