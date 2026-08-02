package com.enginertugrul.iottemperaturemonitor.config;

import com.enginertugrul.iottemperaturemonitor.repository.AppUserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;




@Configuration
public class I18nConfig {

    @Bean
    public LocaleResolver localeResolver(AppUserRepository appUserRepository) {
        return new UserPreferenceLocaleResolver(appUserRepository);
    }
}