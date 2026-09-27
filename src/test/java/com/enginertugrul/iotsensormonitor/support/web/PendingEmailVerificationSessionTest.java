package com.enginertugrul.iotsensormonitor.support.web;

import com.enginertugrul.iotsensormonitor.entity.user.PreferredLanguage;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;



class PendingEmailVerificationSessionTest {

    private static final String EMAIL_ATTRIBUTE = PendingEmailVerificationSession.class.getName() + ".email";



    @ParameterizedTest
    @EnumSource(PreferredLanguage.class)
    void createsSessionWithNormalizedEmailAndPreferredLanguage(PreferredLanguage language) {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(request.getSession(false)).isNull();

        PendingEmailVerificationSession.start(request," OWNER@Example.COM ",language);

        assertThat(request.getSession(false)).isNotNull();
        assertThat(PendingEmailVerificationSession.findEmail(request)).contains("owner@example.com");
        assertThat(PublicLocaleSession.findPreferredLanguage(request)).contains(language);
    }



    @Test
    void rotatesExistingSessionAndReplacesPendingAddressWithoutLosingOtherState() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        PendingPasswordRecoverySession.start(request,"recovery@example.com");
        PendingEmailVerificationSession.start(request,"first@example.com",PreferredLanguage.ENGLISH);
        HttpSession session = request.getSession(false);
        session.setAttribute("unrelated","preserved");
        String originalSessionId = session.getId();

        PendingEmailVerificationSession.start(request," SECOND@Example.COM ",PreferredLanguage.TURKISH);

        assertThat(request.getSession(false)).isSameAs(session);
        assertThat(session.getId()).isNotEqualTo(originalSessionId);
        assertThat(session.getAttribute("unrelated")).isEqualTo("preserved");
        assertThat(PendingEmailVerificationSession.findEmail(request)).contains("second@example.com");
        assertThat(PendingPasswordRecoverySession.findEmail(request)).contains("recovery@example.com");
        assertThat(PublicLocaleSession.findPreferredLanguage(request)).contains(PreferredLanguage.TURKISH);
    }



    @Test
    void findingAndClearingMissingPendingStateDoesNotCreateSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(PendingEmailVerificationSession.findEmail(request)).isEmpty();
        assertThat(request.getSession(false)).isNull();

        PendingEmailVerificationSession.clearEmail(request);

        assertThat(request.getSession(false)).isNull();
    }



    @Test
    void ignoresMissingOrNonStringEmailAttributeWithoutChangingSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        HttpSession session = request.getSession();
        String originalSessionId = session.getId();

        assertThat(PendingEmailVerificationSession.findEmail(request)).isEmpty();

        session.setAttribute(EMAIL_ATTRIBUTE,42L);

        assertThat(PendingEmailVerificationSession.findEmail(request)).isEmpty();
        assertThat(session.getAttribute(EMAIL_ATTRIBUTE)).isEqualTo(42L);
        assertThat(session.getId()).isEqualTo(originalSessionId);
    }



    @Test
    void clearsOnlyVerificationEmailAndRotatesSessionWhilePreservingLocaleAndRecovery() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        PendingEmailVerificationSession.start(request,"owner@example.com",PreferredLanguage.TURKISH);
        PendingPasswordRecoverySession.start(request,"recovery@example.com");
        HttpSession session = request.getSession(false);
        session.setAttribute("unrelated","preserved");
        String originalSessionId = session.getId();

        PendingEmailVerificationSession.clearEmail(request);

        assertThat(request.getSession(false)).isSameAs(session);
        assertThat(session.getId()).isNotEqualTo(originalSessionId);
        assertThat(PendingEmailVerificationSession.findEmail(request)).isEmpty();
        assertThat(PendingPasswordRecoverySession.findEmail(request)).contains("recovery@example.com");
        assertThat(PublicLocaleSession.findPreferredLanguage(request)).contains(PreferredLanguage.TURKISH);
        assertThat(session.getAttribute("unrelated")).isEqualTo("preserved");
    }



    @Test
    void clearingExistingSessionWithoutPendingEmailStillRotatesItsId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        HttpSession session = request.getSession();
        session.setAttribute("unrelated","preserved");
        String originalSessionId = session.getId();

        PendingEmailVerificationSession.clearEmail(request);

        assertThat(request.getSession(false)).isSameAs(session);
        assertThat(session.getId()).isNotEqualTo(originalSessionId);
        assertThat(session.getAttribute("unrelated")).isEqualTo("preserved");
        assertThat(PendingEmailVerificationSession.findEmail(request)).isEmpty();
    }



    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "," \t\n "})
    void rejectsBlankPendingEmail(String email) {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatThrownBy(() -> PendingEmailVerificationSession.start(request,email,PreferredLanguage.ENGLISH))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(PendingEmailVerificationSession.findEmail(request)).isEmpty();
        assertThat(PublicLocaleSession.findPreferredLanguage(request)).isEmpty();
    }



    @Test
    void rejectsMissingRequestOrLanguageBeforeCreatingSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatThrownBy(() -> PendingEmailVerificationSession.start(null,"owner@example.com",PreferredLanguage.ENGLISH))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("request must not be null");

        assertThatThrownBy(() -> PendingEmailVerificationSession.start(request,"owner@example.com",null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("preferredLanguage must not be null");

        assertThat(request.getSession(false)).isNull();
    }
}