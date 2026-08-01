package com.enginertugrul.iottemperaturemonitor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;




@Configuration
public class PasswordRecoveryMailConfig {

    public static final String PASSWORD_RECOVERY_MAIL_EXECUTOR = "passwordRecoveryMailExecutor";


    @Bean(name = PASSWORD_RECOVERY_MAIL_EXECUTOR)
    public ThreadPoolTaskExecutor passwordRecoveryMailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("password-recovery-mail-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }


}