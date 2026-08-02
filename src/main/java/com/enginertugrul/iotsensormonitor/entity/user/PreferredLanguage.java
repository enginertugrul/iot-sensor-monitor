package com.enginertugrul.iotsensormonitor.entity.user;

import lombok.Getter;

import java.util.Locale;

@Getter
public enum PreferredLanguage {

    ENGLISH("en", "English"),
    TURKISH("tr", "Turkish");

    private final String localeTag;
    private final String displayName;

    PreferredLanguage(String localeTag, String displayName) {
        this.localeTag = localeTag;
        this.displayName = displayName;
    }

    public Locale toLocale() {
        return Locale.forLanguageTag(localeTag);
    }
}
