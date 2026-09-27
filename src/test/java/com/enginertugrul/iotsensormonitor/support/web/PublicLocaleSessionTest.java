package com.enginertugrul.iotsensormonitor.support.web;

import com.enginertugrul.iotsensormonitor.entity.user.PreferredLanguage;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;



class PublicLocaleSessionTest {

    private static final String LANGUAGE_ATTRIBUTE = PublicLocaleSession.class.getName() + ".language";



    @ParameterizedTest
    @EnumSource(PreferredLanguage.class)
    void remembersPreferredLanguageInNewSession(PreferredLanguage language) {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(request.getSession(false)).isNull();

        PublicLocaleSession.remember(request,language);

        assertThat(request.getSession(false)).isNotNull();
        assertThat(PublicLocaleSession.findPreferredLanguage(request)).contains(language);
    }



    @Test
    void replacesRememberedLanguageWithoutRotatingSessionOrRemovingOtherAttributes() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        PublicLocaleSession.remember(request,PreferredLanguage.TURKISH);
        HttpSession session = request.getSession(false);
        session.setAttribute("unrelated","preserved");
        String originalSessionId = session.getId();

        PublicLocaleSession.remember(request,PreferredLanguage.ENGLISH);

        assertThat(PublicLocaleSession.findPreferredLanguage(request)).contains(PreferredLanguage.ENGLISH);
        assertThat(request.getSession(false)).isSameAs(session);
        assertThat(session.getId()).isEqualTo(originalSessionId);
        assertThat(session.getAttribute("unrelated")).isEqualTo("preserved");
    }



    @ParameterizedTest
    @MethodSource("localeMappings")
    void mapsLocaleLanguageAndFallsBackToEnglishForUnsupportedLocales(Locale locale,PreferredLanguage expected) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        PublicLocaleSession.remember(request,PreferredLanguage.TURKISH);
        HttpSession session = request.getSession(false);
        String originalSessionId = session.getId();

        PublicLocaleSession.remember(request,locale);

        assertThat(PublicLocaleSession.findPreferredLanguage(request)).contains(expected);
        assertThat(request.getSession(false)).isSameAs(session);
        assertThat(session.getId()).isEqualTo(originalSessionId);
    }



    @Test
    void findingLanguageWithoutSessionDoesNotCreateOne() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(PublicLocaleSession.findPreferredLanguage(request)).isEmpty();
        assertThat(request.getSession(false)).isNull();
    }



    @Test
    void ignoresMissingOrWrongTypeLanguageAttributeWithoutChangingSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        HttpSession session = request.getSession();
        String originalSessionId = session.getId();

        assertThat(PublicLocaleSession.findPreferredLanguage(request)).isEmpty();

        session.setAttribute(LANGUAGE_ATTRIBUTE,"tr");

        assertThat(PublicLocaleSession.findPreferredLanguage(request)).isEmpty();
        assertThat(session.getAttribute(LANGUAGE_ATTRIBUTE)).isEqualTo("tr");
        assertThat(session.getId()).isEqualTo(originalSessionId);
    }



    @Test
    void rejectsNullLanguageAndLocaleBeforeCreatingSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatThrownBy(() -> PublicLocaleSession.remember(request,(PreferredLanguage) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("preferredLanguage must not be null");

        assertThatThrownBy(() -> PublicLocaleSession.remember(request,(Locale) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("locale must not be null");

        assertThat(request.getSession(false)).isNull();
    }



    @Test
    void rejectsMissingRequestForBothOverloads() {
        assertThatThrownBy(() -> PublicLocaleSession.remember(null,PreferredLanguage.ENGLISH))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("request must not be null");

        assertThatThrownBy(() -> PublicLocaleSession.remember(null,Locale.ENGLISH))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("request must not be null");
    }



    private static Stream<Arguments> localeMappings() {
        return Stream.of(
                Arguments.of(Locale.ENGLISH,PreferredLanguage.ENGLISH),
                Arguments.of(Locale.US,PreferredLanguage.ENGLISH),
                Arguments.of(Locale.UK,PreferredLanguage.ENGLISH),
                Arguments.of(Locale.forLanguageTag("tr"),PreferredLanguage.TURKISH),
                Arguments.of(Locale.forLanguageTag("tr-TR"),PreferredLanguage.TURKISH),
                Arguments.of(Locale.forLanguageTag("tr-CY"),PreferredLanguage.TURKISH),
                Arguments.of(Locale.GERMANY,PreferredLanguage.ENGLISH),
                Arguments.of(Locale.JAPAN,PreferredLanguage.ENGLISH),
                Arguments.of(Locale.ROOT,PreferredLanguage.ENGLISH));
    }
}