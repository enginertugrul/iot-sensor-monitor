package com.enginertugrul.iotsensormonitor.security;

import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.entity.user.PreferredLanguage;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

public class AuthenticatedUser implements UserDetails, CredentialsContainer {

    private final Long appUserId;
    private final String username;
    private String password;
    private final boolean enabled;
    private final boolean emailVerified;
    private final PreferredLanguage preferredLanguage;
    private final List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));

    public AuthenticatedUser(AppUser appUser) {
        this.appUserId = appUser.getId();
        this.username = appUser.getEmail();
        this.password = appUser.getPasswordHash();
        this.enabled = appUser.isEnabled();
        this.emailVerified = appUser.isEmailVerified();
        this.preferredLanguage = appUser.getPreferredLanguage();
    }

    public Long getAppUserId() {
        return appUserId;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public PreferredLanguage getPreferredLanguage() {
        return preferredLanguage;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }

        if (!(object instanceof AuthenticatedUser authenticatedUser)) {
            return false;
        }

        return appUserId.equals(authenticatedUser.appUserId);
    }

    @Override
    public int hashCode() {
        return appUserId.hashCode();
    }

    @Override
    public void eraseCredentials() {
        this.password = null;
    }
}