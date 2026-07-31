package com.enginertugrul.iottemperaturemonitor.config;

import com.enginertugrul.iottemperaturemonitor.security.EmailVerificationAuthenticationSuccessHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            EmailVerificationAuthenticationSuccessHandler emailVerificationAuthenticationSuccessHandler
    ) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                HttpMethod.GET,
                                "/login",
                                "/register",
                                "/verify-email",
                                "/favicon.png"
                        ).permitAll()
                        .requestMatchers(
                                HttpMethod.POST,
                                "/login",
                                "/register",
                                "/verify-email",
                                "/verify-email/request",
                                "/verify-email/resend",
                                "/verify-email/change-address"
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
}
