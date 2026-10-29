package com.enginertugrul.iotsensormonitor.entity.user;

import lombok.Getter;

import java.util.Locale;

@Getter
public enum PreferredLanguage {

    ENGLISH("en"),
    TURKISH("tr");

    private final String localeTag;

    PreferredLanguage(String localeTag) {
        this.localeTag = localeTag;
    }

    public Locale toLocale() {
        return Locale.forLanguageTag(localeTag);
    }
}