package com.enginertugrul.iotsensormonitor.service.notification;

import com.enginertugrul.iotsensormonitor.service.user.verification.EmailVerificationCodeDelivery;
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
public class EmailVerificationNotificationSender implements EmailVerificationNotificationDispatcher {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final MessageSource messageSource;
    private final boolean verificationEmailsEnabled;
    private final String fromAddress;

    public EmailVerificationNotificationSender(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            MessageSource messageSource,
            @Value("${app.mail.email-verification.enabled:true}") boolean verificationEmailsEnabled,
            @Value("${spring.mail.username}") String fromAddress
    ) {
        this.mailSenderProvider = mailSenderProvider;
        this.messageSource = messageSource;
        this.verificationEmailsEnabled = verificationEmailsEnabled;
        this.fromAddress = requireText(fromAddress,"fromAddress");
    }

    @Override
    public void send(EmailVerificationCodeDelivery delivery) {
        EmailVerificationCodeDelivery requiredDelivery =
                Objects.requireNonNull(delivery, "delivery must not be null");

        if (!verificationEmailsEnabled) {
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
        message.setSubject(messageSource.getMessage("email.verification.subject",null,locale));
        message.setText(messageSource.getMessage(
                "email.verification.body",
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