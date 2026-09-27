package com.enginertugrul.iotsensormonitor.support.web;

import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.entity.user.PreferredLanguage;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.repository.AppUserRepository;
import com.enginertugrul.iotsensormonitor.security.AuthenticatedUser;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.UPDATED_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;



@ExtendWith(MockitoExtension.class)
class UserPreferenceLocaleResolverTest {

    private static final Long USER_ID = 42L;

    @Mock
    private AppUserRepository appUserRepository;

    private UserPreferenceLocaleResolver resolver;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        resolver = new UserPreferenceLocaleResolver(appUserRepository);
        request = new MockHttpServletRequest();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }



    @Test
    void defaultsToEnglishWithoutAuthenticationOrSessionDespiteBrowserAndRequestPreferences() {
        request.setPreferredLocales(List.of(Locale.forLanguageTag("tr-TR")));
        request.addParameter("lang","tr");

        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.ENGLISH);

        assertThat(request.getSession(false)).isNull();
        verifyNoInteractions(appUserRepository);
    }



    @ParameterizedTest
    @EnumSource(PreferredLanguage.class)
    void usesPublicSessionLanguageWithoutAuthentication(PreferredLanguage language) {
        PreferredLanguage browserLanguage = opposite(language);
        PublicLocaleSession.remember(request,language);
        request.setPreferredLocales(List.of(browserLanguage.toLocale()));
        request.addParameter("lang",browserLanguage.toLocale().getLanguage());
        HttpSession session = request.getSession(false);
        String originalSessionId = session.getId();

        assertThat(resolver.resolveLocale(request)).isEqualTo(language.toLocale());

        assertThat(request.getSession(false)).isSameAs(session);
        assertThat(session.getId()).isEqualTo(originalSessionId);
        verifyNoInteractions(appUserRepository);
    }



    @ParameterizedTest
    @MethodSource("nonAccountAuthentications")
    void usesPublicLanguageForAnonymousUnsupportedOrUnauthenticatedPrincipals(Authentication authentication) {
        PublicLocaleSession.remember(request,PreferredLanguage.TURKISH);
        installAuthentication(authentication);
        request.setPreferredLocales(List.of(Locale.ENGLISH));

        assertThat(resolver.resolveLocale(request)).isEqualTo(PreferredLanguage.TURKISH.toLocale());

        verifyNoInteractions(appUserRepository);
    }



    @ParameterizedTest
    @EnumSource(PreferredLanguage.class)
    void storedPreferenceOverridesPrincipalSnapshotPublicSessionBrowserAndRequestParameter(PreferredLanguage storedLanguage) {
        PreferredLanguage previousLanguage = opposite(storedLanguage);
        AppUser user = user(previousLanguage);
        AuthenticatedUser principal = authenticate(user);
        user.updatePreferences(storedLanguage,TemperatureUnit.CELSIUS,"UTC",UPDATED_AT);
        when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        PublicLocaleSession.remember(request,previousLanguage);
        request.setPreferredLocales(List.of(previousLanguage.toLocale()));
        request.addParameter("lang",previousLanguage.toLocale().getLanguage());

        assertThat(resolver.resolveLocale(request)).isEqualTo(storedLanguage.toLocale());

        assertThat(principal.getPreferredLanguage()).isEqualTo(previousLanguage);
        assertThat(PublicLocaleSession.findPreferredLanguage(request)).contains(previousLanguage);
        verify(appUserRepository).findById(USER_ID);
    }



    @Test
    void readsUpdatedPreferenceOnEachResolutionWithoutCreatingSession() {
        AppUser user = user(PreferredLanguage.ENGLISH);
        AuthenticatedUser principal = authenticate(user);
        when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.ENGLISH);

        user.updatePreferences(PreferredLanguage.TURKISH,TemperatureUnit.CELSIUS,"UTC",UPDATED_AT);

        assertThat(resolver.resolveLocale(request)).isEqualTo(PreferredLanguage.TURKISH.toLocale());
        assertThat(principal.getPreferredLanguage()).isEqualTo(PreferredLanguage.ENGLISH);
        assertThat(request.getSession(false)).isNull();
        verify(appUserRepository,times(2)).findById(USER_ID);
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void missingStoredUserFallsBackToPublicLanguageOrEnglish(boolean hasPublicLanguage) {
        PreferredLanguage snapshotLanguage = hasPublicLanguage ? PreferredLanguage.ENGLISH : PreferredLanguage.TURKISH;
        authenticate(user(snapshotLanguage));
        when(appUserRepository.findById(USER_ID)).thenReturn(Optional.empty());
        request.setPreferredLocales(List.of(snapshotLanguage.toLocale()));

        if (hasPublicLanguage) {
            PublicLocaleSession.remember(request,PreferredLanguage.TURKISH);
        }

        Locale expected = hasPublicLanguage ? PreferredLanguage.TURKISH.toLocale() : Locale.ENGLISH;

        assertThat(resolver.resolveLocale(request)).isEqualTo(expected);

        if (hasPublicLanguage) {
            assertThat(PublicLocaleSession.findPreferredLanguage(request)).contains(PreferredLanguage.TURKISH);
        } else {
            assertThat(request.getSession(false)).isNull();
        }
        verify(appUserRepository).findById(USER_ID);
    }



    @Test
    void defaultsToEnglishWhenPublicSessionLanguageHasWrongType() {
        request.getSession().setAttribute(PublicLocaleSession.class.getName() + ".language","tr");
        request.setPreferredLocales(List.of(Locale.forLanguageTag("tr")));

        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.ENGLISH);

        verifyNoInteractions(appUserRepository);
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void setLocaleDoesNotCreateSessionOrChangeRememberedLanguage(boolean existingSession) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        if (existingSession) {
            PublicLocaleSession.remember(request,PreferredLanguage.ENGLISH);
            request.getSession(false).setAttribute("unrelated","preserved");
        }
        HttpSession session = request.getSession(false);
        String originalSessionId = session == null ? null : session.getId();

        resolver.setLocale(request,response,PreferredLanguage.TURKISH.toLocale());

        if (existingSession) {
            assertThat(request.getSession(false)).isSameAs(session);
            assertThat(session.getId()).isEqualTo(originalSessionId);
            assertThat(session.getAttribute("unrelated")).isEqualTo("preserved");
            assertThat(PublicLocaleSession.findPreferredLanguage(request)).contains(PreferredLanguage.ENGLISH);
        } else {
            assertThat(request.getSession(false)).isNull();
        }
        assertThat(response.isCommitted()).isFalse();
        verifyNoInteractions(appUserRepository);
    }



    private static AuthenticatedUser authenticate(AppUser user) {
        AuthenticatedUser principal = new AuthenticatedUser(user);
        principal.eraseCredentials();
        installAuthentication(new UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities()));
        return principal;
    }



    private static void installAuthentication(Authentication authentication) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }



    private static AppUser user(PreferredLanguage language) {
        AppUser user = new AppUser("owner@example.com","stored-password-hash",language,TemperatureUnit.CELSIUS,"UTC",CREATED_AT);
        ReflectionTestUtils.setField(user,"id",USER_ID);
        user.verifyEmail(CREATED_AT);
        return user;
    }



    private static PreferredLanguage opposite(PreferredLanguage language) {
        return language == PreferredLanguage.ENGLISH ? PreferredLanguage.TURKISH : PreferredLanguage.ENGLISH;
    }



    private static Stream<Authentication> nonAccountAuthentications() {
        return Stream.of(
                new AnonymousAuthenticationToken("test-key","anonymousUser",AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")),
                new UsernamePasswordAuthenticationToken("owner@example.com",null,AuthorityUtils.createAuthorityList("ROLE_USER")),
                new UsernamePasswordAuthenticationToken(new AuthenticatedUser(user(PreferredLanguage.ENGLISH)),null));
    }
}