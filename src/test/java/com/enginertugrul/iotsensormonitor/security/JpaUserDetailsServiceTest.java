package com.enginertugrul.iotsensormonitor.security;

import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.entity.user.PreferredLanguage;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.repository.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.UPDATED_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;



@ExtendWith(MockitoExtension.class)
class JpaUserDetailsServiceTest {

    @Mock
    private AppUserRepository appUserRepository;

    private JpaUserDetailsService service;

    @BeforeEach
    void setUp() {
        service = new JpaUserDetailsService(appUserRepository);
    }



    @ParameterizedTest
    @CsvSource({"true,true","true,false","false,true","false,false"})
    void loadsActualPrincipalThroughNormalizedEmailAndPreservesAccountFlags(boolean enabled,boolean verified) {
        AppUser user = new AppUser("iot.user@example.com","stored-password-hash",PreferredLanguage.TURKISH,TemperatureUnit.FAHRENHEIT,"Europe/Istanbul",CREATED_AT);
        ReflectionTestUtils.setField(user,"id",42L);
        if (verified) {
            user.verifyEmail(UPDATED_AT);
        }
        if (!enabled) {
            user.disable(UPDATED_AT);
        }
        when(appUserRepository.findByEmail("iot.user@example.com")).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername(" IOT.USER@Example.COM ");

        assertThat(details).isInstanceOf(AuthenticatedUser.class);
        AuthenticatedUser principal = (AuthenticatedUser) details;
        assertThat(principal.getAppUserId()).isEqualTo(42L);
        assertThat(principal.getUsername()).isEqualTo("iot.user@example.com");
        assertThat(principal.getPassword()).isEqualTo("stored-password-hash");
        assertThat(principal.getPreferredLanguage()).isEqualTo(PreferredLanguage.TURKISH);
        assertThat(principal.isEnabled()).isEqualTo(enabled);
        assertThat(principal.isEmailVerified()).isEqualTo(verified);
        assertThat(principal.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_USER");
        verify(appUserRepository).findByEmail("iot.user@example.com");
    }



    @Test
    void reportsMissingUserAfterNormalizedLookup() {
        when(appUserRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername(" MISSING@Example.COM "))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessage("User not found");

        verify(appUserRepository).findByEmail("missing@example.com");
    }



    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "," \t\n "})
    void rejectsBlankUsernameBeforeRepositoryAccess(String username) {
        assertThatThrownBy(() -> service.loadUserByUsername(username))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("email must not be blank");

        verifyNoInteractions(appUserRepository);
    }
}