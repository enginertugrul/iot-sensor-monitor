package com.enginertugrul.iotsensormonitor.service.notification.verification;

import com.enginertugrul.iotsensormonitor.service.user.verification.EmailVerificationCodeDelivery;
import com.enginertugrul.iotsensormonitor.service.user.verification.EmailVerificationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;



@Service
public class EmailVerificationNotificationSender implements EmailVerificationNotificationDispatcher {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final MessageSource messageSource;
    private final EmailVerificationService emailVerificationService;
    private final boolean verificationEmailsEnabled;
    private final String fromAddress;
    private final Clock clock;


    public EmailVerificationNotificationSender(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            MessageSource messageSource,
            EmailVerificationService emailVerificationService,
            @Value("${app.mail.email-verification.enabled:true}") boolean verificationEmailsEnabled,
            @Value("${spring.mail.username}") String fromAddress,
            Clock clock
    ) {
        this.mailSenderProvider = mailSenderProvider;
        this.messageSource = messageSource;
        this.emailVerificationService = emailVerificationService;
        this.verificationEmailsEnabled = verificationEmailsEnabled;
        this.fromAddress = requireText(fromAddress,"fromAddress");
        this.clock = clock;
    }

    @Override
    public void send(EmailVerificationCodeDelivery delivery) {
        Objects.requireNonNull(delivery,"delivery must not be null");

        if (!verificationEmailsEnabled || !emailVerificationService.canDeliverCode(delivery)) {
            return;
        }

        long remainingMinutes = calculateRemainingMinutes(delivery.expiresAt());

        if (remainingMinutes < 1) {
            return;
        }

        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();

        if (mailSender == null) {
            throw new IllegalStateException("JavaMailSender is unavailable");
        }

        Locale locale = delivery.preferredLanguage().toLocale();

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(delivery.recipientEmail());
        message.setSubject(messageSource.getMessage("email.verification.subject",null, locale));
        message.setText(messageSource.getMessage("email.verification.body",new Object[]{delivery.rawCode(), remainingMinutes}, locale));

        mailSender.send(message);
    }

    private long calculateRemainingMinutes(Instant expiresAt) {
        long remainingSeconds = Duration.between(clock.instant(), expiresAt).getSeconds();

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