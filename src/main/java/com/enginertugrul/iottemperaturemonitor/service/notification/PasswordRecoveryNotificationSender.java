package com.enginertugrul.iottemperaturemonitor.service.notification;

import com.enginertugrul.iottemperaturemonitor.service.user.recovery.PasswordRecoveryCodeDelivery;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;





@Service
public class PasswordRecoveryNotificationSender implements PasswordRecoveryNotificationDispatcher {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final MessageSource messageSource;
    private final boolean passwordRecoveryEmailsEnabled;
    private final String fromAddress;




    public PasswordRecoveryNotificationSender(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            MessageSource messageSource,
            @Value("${app.mail.password-recovery.enabled:true}") boolean passwordRecoveryEmailsEnabled,
            @Value("${spring.mail.username}") String fromAddress
    ) {
        this.mailSenderProvider = mailSenderProvider;
        this.messageSource = messageSource;
        this.passwordRecoveryEmailsEnabled = passwordRecoveryEmailsEnabled;
        this.fromAddress = requireText(fromAddress,"fromAddress");
    }




    @Override
    public void send(PasswordRecoveryCodeDelivery delivery) {
        PasswordRecoveryCodeDelivery requiredDelivery = Objects.requireNonNull(delivery,"delivery must not be null");

        if (!passwordRecoveryEmailsEnabled) {
            return;
        }

        long remainingMinutes = calculateRemainingMinutes(requiredDelivery.expiresAt());

        if (remainingMinutes < 1) {
            return;
        }

        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();

        if (mailSender == null) {
            throw new IllegalStateException("JavaMailSender is unavailable");
        }

        Locale locale = requiredDelivery.preferredLanguage().toLocale();

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(requiredDelivery.recipientEmail());
        message.setSubject(messageSource.getMessage("email.passwordRecovery.subject",null,locale));
        message.setText(messageSource.getMessage(
                "email.passwordRecovery.body",
                new Object[]{requiredDelivery.rawCode(),remainingMinutes},
                locale
        ));

        mailSender.send(message);
    }





    private long calculateRemainingMinutes(Instant expiresAt) {
        long remainingSeconds = Duration.between(Instant.now(),expiresAt).getSeconds();

        if (remainingSeconds <= 0) {
            return 0;
        }

        return (remainingSeconds + 59) / 60;
    }




    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }

        return value.trim();
    }
}