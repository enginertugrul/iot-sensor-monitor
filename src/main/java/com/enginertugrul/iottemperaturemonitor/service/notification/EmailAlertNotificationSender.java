package com.enginertugrul.iottemperaturemonitor.service.notification;

import com.enginertugrul.iottemperaturemonitor.service.alert.AlertTriggeredEvent;
import com.enginertugrul.iottemperaturemonitor.support.temperature.TemperatureUnitConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.text.NumberFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;


@Service
public class EmailAlertNotificationSender implements AlertNotificationDispatcher {


    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final MessageSource messageSource;
    private final TemperatureUnitConverter temperatureUnitConverter;
    private final boolean alertsEnabled;
    private final String fromAddress;


    public EmailAlertNotificationSender(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            MessageSource messageSource,
            TemperatureUnitConverter temperatureUnitConverter,
            @Value("${app.mail.alerts.enabled}") boolean alertsEnabled,
            @Value("${spring.mail.username}") String fromAddress
    ) {
        this.mailSenderProvider = mailSenderProvider;
        this.messageSource = messageSource;
        this.temperatureUnitConverter = temperatureUnitConverter;
        this.alertsEnabled = alertsEnabled;
        this.fromAddress = fromAddress;
    }

    @Override
    public void send(AlertTriggeredEvent event) {
        if (!alertsEnabled) {
            return;
        }

        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();

        if (mailSender == null) {
            return;
        }

        Locale locale = event.preferredLanguage().toLocale();

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(event.recipientEmail());
        message.setSubject(buildSubject(event, locale));
        message.setText(buildBody(event, locale));

        mailSender.send(message);
    }


    private String buildSubject(AlertTriggeredEvent event, Locale locale) {
        return messageSource.getMessage(
                "email.alert.temperature.subject",
                new Object[]{event.sensorName()},
                locale
        );
    }

    private String buildBody(AlertTriggeredEvent event, Locale locale) {
        Double displayReading = temperatureUnitConverter.convertFromCelsius(
                event.readingValueCelsius(),
                event.preferredTemperatureUnit()
        );

        Double displayThreshold = temperatureUnitConverter.convertFromCelsius(
                event.thresholdValueCelsius(),
                event.preferredTemperatureUnit()
        );

        String unitSymbol = temperatureUnitConverter.getSymbol(event.preferredTemperatureUnit());
        String comparison = messageSource.getMessage(
                "comparisonOperator." + event.comparisonOperator().name(),
                null,
                locale
        );

        return messageSource.getMessage(
                "email.alert.temperature.body",
                new Object[]{
                        event.sensorName(),
                        event.sensorHomeLocation(),
                        event.sensorCity(),
                        event.sensorDistrict(),
                        comparison,
                        formatNumber(displayReading, locale),
                        formatNumber(displayThreshold, locale),
                        unitSymbol,
                        formatTimestamp(event, locale),
                        event.cooldownMinutes()
                },
                locale
        );
    }

    private String formatNumber(Double value, Locale locale) {
        NumberFormat formatter = NumberFormat.getNumberInstance(locale);
        formatter.setMinimumFractionDigits(1);
        formatter.setMaximumFractionDigits(2);
        return formatter.format(value);
    }

    private String formatTimestamp(AlertTriggeredEvent event, Locale locale) {
        return DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss z")
                .withLocale(locale)
                .format(event.recordedAt().atZone(ZoneId.of(event.preferredTimezone())));
    }




}
