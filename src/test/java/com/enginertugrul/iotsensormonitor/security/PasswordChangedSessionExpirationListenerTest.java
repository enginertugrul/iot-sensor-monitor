package com.enginertugrul.iotsensormonitor.security;

import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.service.user.password.PasswordChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PasswordChangedSessionExpirationListenerTest {

    private static final Long USER_ID = 42L;

    private SessionRegistry sessionRegistry;
    private PasswordChangedSessionExpirationListener listener;

    @BeforeEach
    void setUp() {
        sessionRegistry = new SessionRegistryImpl();
        listener = new PasswordChangedSessionExpirationListener(sessionRegistry);
    }



    @Test
    void expiresAllMatchingUsersSessionsByIdAndPreservesOtherPrincipalTypesAndUsers() {
        AuthenticatedUser firstPrincipal = principal(USER_ID,"owner@example.com");
        AuthenticatedUser secondPrincipal = principal(USER_ID,"changed@example.com");
        AuthenticatedUser otherPrincipal = principal(43L,"owner@example.com");

        SessionInformation first = registerSession("owner-first",firstPrincipal);
        SessionInformation second = registerSession("owner-second",secondPrincipal);
        SessionInformation alreadyExpired = registerSession("owner-expired",firstPrincipal);
        SessionInformation otherUser = registerSession("other-user",otherPrincipal);
        SessionInformation otherPrincipalType = registerSession("other-principal-type","owner@example.com");
        alreadyExpired.expireNow();

        listener.onPasswordChanged(new PasswordChangedEvent(USER_ID));

        assertThat(first.isExpired()).isTrue();
        assertThat(second.isExpired()).isTrue();
        assertThat(alreadyExpired.isExpired()).isTrue();
        assertThat(otherUser.isExpired()).isFalse();
        assertThat(otherPrincipalType.isExpired()).isFalse();
        assertThat(sessionRegistry.getAllSessions(firstPrincipal,false)).isEmpty();
        assertThat(sessionRegistry.getAllSessions(firstPrincipal,true)).hasSize(3);
        assertThat(sessionRegistry.getSessionInformation("owner-first")).isSameAs(first);

        listener.onPasswordChanged(new PasswordChangedEvent(USER_ID));

        assertThat(otherUser.isExpired()).isFalse();
        assertThat(otherPrincipalType.isExpired()).isFalse();
        assertThat(sessionRegistry.getAllSessions(firstPrincipal,true)).hasSize(3);
    }



    @Test
    void leavesSessionsActiveWhenEventUserHasNoRegisteredSessions() {
        SessionInformation otherUser = registerSession("other-user",principal(43L,"other@example.com"));
        SessionInformation otherPrincipalType = registerSession("other-principal-type","owner@example.com");

        listener.onPasswordChanged(new PasswordChangedEvent(USER_ID));

        assertThat(otherUser.isExpired()).isFalse();
        assertThat(otherPrincipalType.isExpired()).isFalse();
    }



    @Test
    void handlesEmptySessionRegistry() {
        assertThatCode(() -> listener.onPasswordChanged(new PasswordChangedEvent(USER_ID))).doesNotThrowAnyException();

        assertThat(sessionRegistry.getAllPrincipals()).isEmpty();
    }



    @Test
    void containsFailureWhileListingPrincipals() {
        SessionRegistry failingRegistry = mock(SessionRegistry.class);
        when(failingRegistry.getAllPrincipals()).thenThrow(new IllegalStateException("Principal lookup failed"));
        PasswordChangedSessionExpirationListener failingListener = new PasswordChangedSessionExpirationListener(failingRegistry);

        assertThatCode(() -> failingListener.onPasswordChanged(new PasswordChangedEvent(USER_ID))).doesNotThrowAnyException();

        verify(failingRegistry).getAllPrincipals();
    }



    @Test
    void containsFailureWhileListingUsersActiveSessions() {
        SessionRegistry failingRegistry = mock(SessionRegistry.class);
        AuthenticatedUser principal = principal(USER_ID,"owner@example.com");
        when(failingRegistry.getAllPrincipals()).thenReturn(List.of(principal));
        when(failingRegistry.getAllSessions(principal,false)).thenThrow(new IllegalStateException("Session lookup failed"));
        PasswordChangedSessionExpirationListener failingListener = new PasswordChangedSessionExpirationListener(failingRegistry);

        assertThatCode(() -> failingListener.onPasswordChanged(new PasswordChangedEvent(USER_ID))).doesNotThrowAnyException();

        verify(failingRegistry).getAllSessions(principal,false);
    }



    @Test
    void containsFailureWhileExpiringSession() {
        SessionRegistry failingRegistry = mock(SessionRegistry.class);
        SessionInformation failingSession = mock(SessionInformation.class);
        AuthenticatedUser principal = principal(USER_ID,"owner@example.com");
        when(failingRegistry.getAllPrincipals()).thenReturn(List.of(principal));
        when(failingRegistry.getAllSessions(principal,false)).thenReturn(List.of(failingSession));
        doThrow(new IllegalStateException("Session expiration failed")).when(failingSession).expireNow();
        PasswordChangedSessionExpirationListener failingListener = new PasswordChangedSessionExpirationListener(failingRegistry);

        assertThatCode(() -> failingListener.onPasswordChanged(new PasswordChangedEvent(USER_ID))).doesNotThrowAnyException();

        verify(failingSession).expireNow();
    }



    private SessionInformation registerSession(String sessionId,Object principal) {
        sessionRegistry.registerNewSession(sessionId,principal);
        SessionInformation information = sessionRegistry.getSessionInformation(sessionId);
        assertThat(information).isNotNull();
        return information;
    }



    private static AuthenticatedUser principal(Long userId,String email) {
        AppUser user = new AppUser(email,"stored-password-hash",CREATED_AT);
        ReflectionTestUtils.setField(user,"id",userId);
        user.verifyEmail(CREATED_AT);

        AuthenticatedUser principal = new AuthenticatedUser(user);
        principal.eraseCredentials();
        return principal;
    }
}