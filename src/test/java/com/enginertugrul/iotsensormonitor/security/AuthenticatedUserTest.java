package com.enginertugrul.iotsensormonitor.security;

import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.entity.user.PreferredLanguage;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.UPDATED_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;



class AuthenticatedUserTest {



    @Test
    void capturesAccountStateWithoutFollowingLaterEntityChanges() {
        AppUser user = storedUser(42L,"owner@example.com","original-password-hash");
        AuthenticatedUser principal = new AuthenticatedUser(user);

        user.updatePasswordHash("replacement-password-hash",UPDATED_AT);
        user.updatePreferences(PreferredLanguage.TURKISH,TemperatureUnit.FAHRENHEIT,"Europe/Istanbul",UPDATED_AT);
        user.verifyEmail(UPDATED_AT);
        user.disable(UPDATED_AT);

        assertThat(principal.getAppUserId()).isEqualTo(42L);
        assertThat(principal.getUsername()).isEqualTo("owner@example.com");
        assertThat(principal.getPassword()).isEqualTo("original-password-hash");
        assertThat(principal.getPreferredLanguage()).isEqualTo(PreferredLanguage.ENGLISH);
        assertThat(principal.isEnabled()).isTrue();
        assertThat(principal.isEmailVerified()).isFalse();
    }



    @Test
    void suppliesAnImmutableUserAuthorityAndNonExpiringAccountFlags() {
        AuthenticatedUser principal = new AuthenticatedUser(storedUser(42L,"owner@example.com","password-hash"));

        assertThat(principal.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_USER");
        assertThat(principal.isAccountNonExpired()).isTrue();
        assertThat(principal.isAccountNonLocked()).isTrue();
        assertThat(principal.isCredentialsNonExpired()).isTrue();

        assertThatThrownBy(() -> principal.getAuthorities().clear()).isInstanceOf(UnsupportedOperationException.class);

        assertThat(principal.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_USER");
    }



    @Test
    void identifiesPrincipalsByUserIdDespiteDifferentAccountSnapshots() {
        AuthenticatedUser original = new AuthenticatedUser(storedUser(42L,"owner@example.com","old-password-hash"));
        AppUser changedUser = storedUser(42L,"changed@example.com","new-password-hash");
        changedUser.updatePreferences(PreferredLanguage.TURKISH,TemperatureUnit.KELVIN,"Asia/Tokyo",UPDATED_AT);
        changedUser.verifyEmail(UPDATED_AT);
        changedUser.disable(UPDATED_AT);
        AuthenticatedUser changed = new AuthenticatedUser(changedUser);
        AuthenticatedUser third = new AuthenticatedUser(storedUser(42L,"third@example.com","third-password-hash"));

        assertThat(original).isEqualTo(changed).isEqualTo(third);
        assertThat(changed).isEqualTo(original).isEqualTo(third);
        assertThat(third).isEqualTo(original);
        assertThat(original.hashCode()).isEqualTo(changed.hashCode()).isEqualTo(third.hashCode());
        assertThat(new HashSet<>(List.of(original,changed,third))).hasSize(1);
    }



    @Test
    void distinguishesDifferentUserIdsEvenWhenUsernamesMatch() {
        AuthenticatedUser first = new AuthenticatedUser(storedUser(42L,"owner@example.com","password-hash"));
        AuthenticatedUser second = new AuthenticatedUser(storedUser(43L,"owner@example.com","password-hash"));

        assertThat(first).isNotEqualTo(second).isNotEqualTo(null);
        assertThat(second).isNotEqualTo(first);
    }



    @Test
    void erasesCredentialsIdempotentlyWithoutChangingIdentityOrTheEntityPassword() {
        AppUser user = storedUser(42L,"owner@example.com","stored-password-hash");
        user.updatePreferences(PreferredLanguage.TURKISH,TemperatureUnit.FAHRENHEIT,"Europe/Istanbul",UPDATED_AT);
        user.verifyEmail(UPDATED_AT);
        user.disable(UPDATED_AT);
        AuthenticatedUser principal = new AuthenticatedUser(user);
        AuthenticatedUser sameIdentity = new AuthenticatedUser(user);
        Set<AuthenticatedUser> principals = new HashSet<>();
        principals.add(principal);
        int originalHashCode = principal.hashCode();

        principal.eraseCredentials();
        principal.eraseCredentials();

        assertThat(principal.getPassword()).isNull();
        assertThat(principal.getAppUserId()).isEqualTo(42L);
        assertThat(principal.getUsername()).isEqualTo("owner@example.com");
        assertThat(principal.getPreferredLanguage()).isEqualTo(PreferredLanguage.TURKISH);
        assertThat(principal.isEnabled()).isFalse();
        assertThat(principal.isEmailVerified()).isTrue();
        assertThat(principal.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_USER");
        assertThat(principal).isEqualTo(sameIdentity);
        assertThat(principal.hashCode()).isEqualTo(originalHashCode);
        assertThat(principals).contains(principal,sameIdentity);
        assertThat(user.getPasswordHash()).isEqualTo("stored-password-hash");
        assertThat(sameIdentity.getPassword()).isEqualTo("stored-password-hash");
    }



    @Test
    void supportsCredentialErasureThroughTheAuthenticationToken() {
        AppUser user = storedUser(42L,"owner@example.com","stored-password-hash");
        AuthenticatedUser principal = new AuthenticatedUser(user);
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(principal,"submitted-password",principal.getAuthorities());

        authentication.eraseCredentials();

        assertThat(authentication.getCredentials()).isNull();
        assertThat(authentication.getPrincipal()).isSameAs(principal);
        assertThat(principal.getPassword()).isNull();
        assertThat(user.getPasswordHash()).isEqualTo("stored-password-hash");
    }



    private static AppUser storedUser(Long userId,String email,String passwordHash) {
        AppUser user = new AppUser(email,passwordHash,CREATED_AT);
        ReflectionTestUtils.setField(user,"id",userId);
        return user;
    }
}