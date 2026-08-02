package com.enginertugrul.iotsensormonitor.config;

import com.enginertugrul.iotsensormonitor.security.EmailVerificationAuthenticationSuccessHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.SessionLimit;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.session.HttpSessionEventPublisher;




@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            EmailVerificationAuthenticationSuccessHandler emailVerificationAuthenticationSuccessHandler,
            SessionRegistry sessionRegistry
    ) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                HttpMethod.GET,
                                "/login",
                                "/register",
                                "/verify-email",
                                "/forgot-password",
                                "/favicon.png"
                        ).permitAll()
                        .requestMatchers(
                                HttpMethod.POST,
                                "/login",
                                "/register",
                                "/verify-email",
                                "/verify-email/request",
                                "/verify-email/resend",
                                "/verify-email/change-address",
                                "/forgot-password/request",
                                "/forgot-password/reset",
                                "/forgot-password/resend",
                                "/forgot-password/change-address"
                        ).permitAll()
                        .requestMatchers("/css/**", "/js/**", "/images/**", "/webjars/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/readings/temperature" , "/readings/humidity" , "/readings/motion").permitAll()
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .successHandler(emailVerificationAuthenticationSuccessHandler)
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .permitAll()
                )
                .sessionManagement(session -> session
                        .sessionConcurrency(concurrency -> concurrency
                                .maximumSessions(SessionLimit.UNLIMITED)
                                .expiredUrl("/login?sessionExpired")
                                .sessionRegistry(sessionRegistry)
                        )
                )
                .csrf(csrf -> csrf
                        .ignoringRequestMatchers(
                                PathPatternRequestMatcher.pathPattern(
                                        HttpMethod.POST,
                                        "/readings/temperature"
                                ),
                                PathPatternRequestMatcher.pathPattern(
                                        HttpMethod.POST,
                                        "/readings/humidity"
                                ),
                                PathPatternRequestMatcher.pathPattern(
                                        HttpMethod.POST,
                                        "/readings/motion"
                                )
                        )
                );

        return http.build();
    }


    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

}
