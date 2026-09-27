package com.enginertugrul.iotsensormonitor.security;

import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.entity.user.PreferredLanguage;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.support.web.PendingEmailVerificationSession;
import com.enginertugrul.iotsensormonitor.support.web.PendingPasswordRecoverySession;
import com.enginertugrul.iotsensormonitor.support.web.PublicLocaleSession;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.WebAttributes;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.util.ReflectionTestUtils;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;



class EmailVerificationAuthenticationSuccessHandlerTest {

    private EmailVerificationAuthenticationSuccessHandler handler;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        handler = new EmailVerificationAuthenticationSuccessHandler();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }



    @ParameterizedTest
    @ValueSource(strings = {"","/monitor"})
    void redirectsVerifiedUserToApplicationRootAndPreservesAuthenticatedSession(String contextPath) throws Exception {
        MockHttpServletRequest request = request(contextPath);
        MockHttpServletResponse response = new MockHttpServletResponse();
        Authentication authentication = authentication(true,PreferredLanguage.TURKISH);
        SecurityContext context = installAuthentication(authentication);
        MockHttpSession session = (MockHttpSession) request.getSession();
        String originalSessionId = session.getId();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,context);
        session.setAttribute(WebAttributes.AUTHENTICATION_EXCEPTION,new BadCredentialsException("Previous login failed"));
        session.setAttribute("unrelated","preserved");

        handler.setTargetUrlParameter("target");
        handler.setUseReferer(true);
        request.addParameter("target","/sensors");
        request.addHeader("Referer","https://example.com/previous-page");

        handler.onAuthenticationSuccess(request,response,authentication);

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo(contextPath + "/");
        assertThat(request.getSession(false)).isSameAs(session);
        assertThat(session.getId()).isEqualTo(originalSessionId);
        assertThat(session.isInvalid()).isFalse();
        assertThat(session.getAttribute(WebAttributes.AUTHENTICATION_EXCEPTION)).isNull();
        assertThat(session.getAttribute("unrelated")).isEqualTo("preserved");
        assertThat(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isSameAs(context);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(authentication);
        assertThat(PendingEmailVerificationSession.findEmail(request)).isEmpty();
    }



    @Test
    void redirectsVerifiedUserWithoutCreatingMissingSession() throws Exception {
        MockHttpServletRequest request = request("");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Authentication authentication = authentication(true,PreferredLanguage.ENGLISH);
        installAuthentication(authentication);

        assertThat(request.getSession(false)).isNull();

        handler.onAuthenticationSuccess(request,response,authentication);

        assertThat(response.getRedirectedUrl()).isEqualTo("/");
        assertThat(request.getSession(false)).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(authentication);
    }



    @ParameterizedTest
    @EnumSource(PreferredLanguage.class)
    void logsOutUnverifiedUserAndReplacesSessionWithPendingVerification(PreferredLanguage language) throws Exception {
        MockHttpServletRequest request = request("/monitor");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Authentication authentication = authentication(false,language);
        SecurityContext context = installAuthentication(authentication);
        PreferredLanguage previousLanguage = language == PreferredLanguage.ENGLISH ? PreferredLanguage.TURKISH : PreferredLanguage.ENGLISH;

        PendingEmailVerificationSession.start(request,"previous@example.com",previousLanguage);
        PendingPasswordRecoverySession.start(request,"recovery@example.com");
        MockHttpSession previousSession = (MockHttpSession) request.getSession(false);
        previousSession.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,context);
        previousSession.setAttribute("private-state","discarded");
        String previousSessionId = previousSession.getId();

        handler.onAuthenticationSuccess(request,response,authentication);

        HttpSession pendingSession = request.getSession(false);
        assertThat(previousSession.isInvalid()).isTrue();
        assertThat(pendingSession).isNotNull().isNotSameAs(previousSession);
        assertThat(pendingSession.getId()).isNotEqualTo(previousSessionId);
        assertThat(pendingSession.getAttribute("private-state")).isNull();
        assertThat(pendingSession.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNull();
        assertThat(context.getAuthentication()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(PendingEmailVerificationSession.findEmail(request)).contains("owner@example.com");
        assertThat(PendingPasswordRecoverySession.findEmail(request)).isEmpty();
        assertThat(PublicLocaleSession.findPreferredLanguage(request)).contains(language);
        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/monitor/verify-email?verificationRequired=true");
    }



    @Test
    void startsPendingVerificationForUnverifiedUserWithoutExistingSession() throws Exception {
        MockHttpServletRequest request = request("");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Authentication authentication = authentication(false,PreferredLanguage.TURKISH);
        SecurityContext context = installAuthentication(authentication);

        assertThat(request.getSession(false)).isNull();

        handler.onAuthenticationSuccess(request,response,authentication);

        assertThat(request.getSession(false)).isNotNull();
        assertThat(context.getAuthentication()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(PendingEmailVerificationSession.findEmail(request)).contains("owner@example.com");
        assertThat(PublicLocaleSession.findPreferredLanguage(request)).contains(PreferredLanguage.TURKISH);
        assertThat(response.getRedirectedUrl()).isEqualTo("/verify-email?verificationRequired=true");
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void logsOutUnsupportedPrincipalAndRejectsAuthenticationSuccess(boolean existingSession) {
        MockHttpServletRequest request = request("");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Authentication authentication = new UsernamePasswordAuthenticationToken("unsupported-principal",null,AuthorityUtils.createAuthorityList("ROLE_USER"));
        SecurityContext context = installAuthentication(authentication);
        MockHttpSession previousSession = existingSession ? (MockHttpSession) request.getSession() : null;

        if (previousSession != null) {
            previousSession.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,context);
        }

        assertThatThrownBy(() -> handler.onAuthenticationSuccess(request,response,authentication))
                .isInstanceOf(ServletException.class)
                .hasMessage("Unsupported authenticated principal");

        if (previousSession != null) {
            assertThat(previousSession.isInvalid()).isTrue();
        }
        assertThat(request.getSession(false)).isNull();
        assertThat(context.getAuthentication()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getRedirectedUrl()).isNull();
        assertThat(response.isCommitted()).isFalse();
    }



    private static MockHttpServletRequest request(String contextPath) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST",contextPath + "/login");
        request.setContextPath(contextPath);
        return request;
    }



    private static Authentication authentication(boolean verified,PreferredLanguage language) {
        AppUser user = new AppUser(" OWNER@Example.COM ","stored-password-hash",language,TemperatureUnit.CELSIUS,"UTC",CREATED_AT);
        ReflectionTestUtils.setField(user,"id",42L);
        if (verified) {
            user.verifyEmail(CREATED_AT);
        }

        AuthenticatedUser principal = new AuthenticatedUser(user);
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities());
        authentication.eraseCredentials();
        return authentication;
    }



    private static SecurityContext installAuthentication(Authentication authentication) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        return context;
    }
}