package com.enginertugrul.iotsensormonitor.config;

import com.enginertugrul.iotsensormonitor.controller.advice.ApiSecurityExceptionHandler;
import com.enginertugrul.iotsensormonitor.security.EmailVerificationAuthenticationSuccessHandler;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.access.RequestMatcherDelegatingAccessDeniedHandler;
import org.springframework.security.web.authentication.session.SessionLimit;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;


@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            EmailVerificationAuthenticationSuccessHandler emailVerificationAuthenticationSuccessHandler,
            JsonMapper jsonMapper,
            SessionRegistry sessionRegistry
    ) {

        RequestMatcher apiRequestMatcher = PathPatternRequestMatcher.pathPattern("/api/**");
        ApiSecurityExceptionHandler apiSecurityExceptionHandler = new ApiSecurityExceptionHandler(jsonMapper,apiRequestMatcher);

        http
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
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
                .exceptionHandling(exception -> exception
                        .defaultAuthenticationEntryPointFor(apiSecurityExceptionHandler, apiRequestMatcher)
                        .accessDeniedHandler(createAccessDeniedHandler(apiRequestMatcher, apiSecurityExceptionHandler))
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
                                .expiredSessionStrategy(apiSecurityExceptionHandler)
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



    private static AccessDeniedHandler createAccessDeniedHandler(RequestMatcher apiRequestMatcher, ApiSecurityExceptionHandler apiSecurityExceptionHandler) {
        LinkedHashMap<RequestMatcher, AccessDeniedHandler> handlers = new LinkedHashMap<>();
        handlers.put(apiRequestMatcher,apiSecurityExceptionHandler);

        return new RequestMatcherDelegatingAccessDeniedHandler(handlers,new AccessDeniedHandlerImpl());
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
