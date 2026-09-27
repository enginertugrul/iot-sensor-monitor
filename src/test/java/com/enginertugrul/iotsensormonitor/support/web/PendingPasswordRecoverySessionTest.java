package com.enginertugrul.iotsensormonitor.support.web;

import com.enginertugrul.iotsensormonitor.entity.user.PreferredLanguage;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;



class PendingPasswordRecoverySessionTest {

    private static final String EMAIL_ATTRIBUTE = PendingPasswordRecoverySession.class.getName() + ".email";



    @Test
    void createsSessionWithNormalizedRecoveryEmail() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(request.getSession(false)).isNull();

        PendingPasswordRecoverySession.start(request," OWNER@Example.COM ");

        assertThat(request.getSession(false)).isNotNull();
        assertThat(PendingPasswordRecoverySession.findEmail(request)).contains("owner@example.com");
        assertThat(PublicLocaleSession.findPreferredLanguage(request)).isEmpty();
    }



    @Test
    void rotatesExistingSessionAndReplacesRecoveryAddressWithoutLosingOtherState() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        PendingEmailVerificationSession.start(request,"verification@example.com",PreferredLanguage.TURKISH);
        PendingPasswordRecoverySession.start(request,"first@example.com");
        HttpSession session = request.getSession(false);
        session.setAttribute("unrelated","preserved");
        String originalSessionId = session.getId();

        PendingPasswordRecoverySession.start(request," SECOND@Example.COM ");

        assertThat(request.getSession(false)).isSameAs(session);
        assertThat(session.getId()).isNotEqualTo(originalSessionId);
        assertThat(session.getAttribute("unrelated")).isEqualTo("preserved");
        assertThat(PendingPasswordRecoverySession.findEmail(request)).contains("second@example.com");
        assertThat(PendingEmailVerificationSession.findEmail(request)).contains("verification@example.com");
        assertThat(PublicLocaleSession.findPreferredLanguage(request)).contains(PreferredLanguage.TURKISH);
    }



    @Test
    void findingAndClearingMissingPendingStateDoesNotCreateSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(PendingPasswordRecoverySession.findEmail(request)).isEmpty();
        assertThat(request.getSession(false)).isNull();

        PendingPasswordRecoverySession.clearEmail(request);

        assertThat(request.getSession(false)).isNull();
    }



    @Test
    void ignoresMissingOrNonStringEmailAttributeWithoutChangingSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        HttpSession session = request.getSession();
        String originalSessionId = session.getId();

        assertThat(PendingPasswordRecoverySession.findEmail(request)).isEmpty();

        session.setAttribute(EMAIL_ATTRIBUTE,42L);

        assertThat(PendingPasswordRecoverySession.findEmail(request)).isEmpty();
        assertThat(session.getAttribute(EMAIL_ATTRIBUTE)).isEqualTo(42L);
        assertThat(session.getId()).isEqualTo(originalSessionId);
    }



    @Test
    void clearsOnlyRecoveryEmailWithoutRotatingSessionOrDiscardingOtherState() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        PendingEmailVerificationSession.start(request,"verification@example.com",PreferredLanguage.TURKISH);
        PendingPasswordRecoverySession.start(request,"recovery@example.com");
        HttpSession session = request.getSession(false);
        session.setAttribute("unrelated","preserved");
        String originalSessionId = session.getId();

        PendingPasswordRecoverySession.clearEmail(request);
        PendingPasswordRecoverySession.clearEmail(request);

        assertThat(request.getSession(false)).isSameAs(session);
        assertThat(session.getId()).isEqualTo(originalSessionId);
        assertThat(PendingPasswordRecoverySession.findEmail(request)).isEmpty();
        assertThat(PendingEmailVerificationSession.findEmail(request)).contains("verification@example.com");
        assertThat(PublicLocaleSession.findPreferredLanguage(request)).contains(PreferredLanguage.TURKISH);
        assertThat(session.getAttribute("unrelated")).isEqualTo("preserved");
    }



    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "," \t\n "})
    void rejectsBlankPendingEmail(String email) {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatThrownBy(() -> PendingPasswordRecoverySession.start(request,email))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(PendingPasswordRecoverySession.findEmail(request)).isEmpty();
    }



    @Test
    void rejectsMissingRequest() {
        assertThatThrownBy(() -> PendingPasswordRecoverySession.start(null,"owner@example.com"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("request must not be null");
    }
}