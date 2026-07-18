package com.enginertugrul.iottemperaturemonitor.service.notification;

import com.enginertugrul.iottemperaturemonitor.entity.reading.MeasurementUnit;
import com.enginertugrul.iottemperaturemonitor.entity.sensor.SensorType;
import com.enginertugrul.iottemperaturemonitor.service.alert.AlertTriggeredEvent;
import com.enginertugrul.iottemperaturemonitor.service.alert.AlertTriggeredEvent.Context;
import com.enginertugrul.iottemperaturemonitor.service.alert.AlertTriggeredEvent.MotionDetectedTrigger;
import com.enginertugrul.iottemperaturemonitor.service.alert.AlertTriggeredEvent.NumericThresholdTrigger;
import com.enginertugrul.iottemperaturemonitor.support.temperature.TemperatureUnitConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;



@Service
public class EmailAlertNotificationSender implements AlertNotificationDispatcher {

    private static final String HUMIDITY_SYMBOL_KEY = "measurement.relativeHumidity.symbol";

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

        AlertTriggeredEvent requiredEvent = Objects.requireNonNull(event, "event must not be null");

        if (!alertsEnabled) {
            return;
        }

        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();

        if (mailSender == null) {
            return;
        }

        Context context = requiredEvent.context();

        Locale locale = context.recipient().language().toLocale();

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(context.recipient().email());
        message.setSubject(buildSubject(context, locale));
        message.setText(buildBody(requiredEvent,locale));

        mailSender.send(message);
    }


    private String buildSubject(Context context,Locale locale) {
        return messageSource.getMessage("email.alert." + sensorMessageSegment(context.sensor().type()) + ".subject",
                new Object[]{ context.sensor().name() },
                locale );
    }

    private String buildBody(AlertTriggeredEvent event,Locale locale) {

        return switch (event.trigger()) {

            case NumericThresholdTrigger trigger ->
                    buildNumericBody(event.context(),trigger,locale);

            case MotionDetectedTrigger ignored ->
                    buildMotionBody(event.context(),locale);
        };
    }

    private String buildNumericBody(Context context, NumericThresholdTrigger trigger, Locale locale) {

        DisplayValues displayValues =
                toDisplayValues(context, trigger, locale);

        String comparison =
                messageSource.getMessage("comparisonOperator." + trigger.comparisonOperator().name(),
                        null,
                        locale);

        return messageSource.getMessage("email.alert." + sensorMessageSegment(context.sensor().type()) + ".body",
                new Object[]{
                        context.sensor().name(),
                        context.sensor().homeLocation(),
                        context.sensor().city(),
                        context.sensor().district(),
                        comparison,
                        formatNumber(displayValues.reading(),locale),
                        formatNumber(displayValues.threshold(),locale),
                        displayValues.unitSymbol(),
                        formatTimestamp(context, locale),
                        context.cooldownMinutes()
                },
                locale);
    }

    private DisplayValues toDisplayValues(Context context, NumericThresholdTrigger trigger, Locale locale) {

        return switch (trigger.unit()) {

            case MeasurementUnit.C -> new DisplayValues(
                    temperatureUnitConverter
                            .convertFromCelsius(
                                    trigger.readingValue(),
                                    context.recipient()
                                            .preferredTemperatureUnit()
                            ),
                    temperatureUnitConverter
                            .convertFromCelsius(
                                    trigger.thresholdValue(),
                                    context.recipient()
                                            .preferredTemperatureUnit()
                            ),
                    temperatureUnitConverter.getSymbol(
                            context.recipient()
                                    .preferredTemperatureUnit()
                    )
            );

            case MeasurementUnit.PERCENT -> new DisplayValues(
                    trigger.readingValue(),
                    trigger.thresholdValue(),
                    messageSource.getMessage(
                            HUMIDITY_SYMBOL_KEY,
                            null,
                            locale
                    )
            );
        };
    }



    private String buildMotionBody( Context context, Locale locale) {

        String eventDescription = messageSource.getMessage("alertEventType.MOTION_DETECTED", null,locale);

        return messageSource.getMessage(
                "email.alert.motion.body",
                new Object[]{
                        context.sensor().name(),
                        context.sensor().homeLocation(),
                        context.sensor().city(),
                        context.sensor().district(),
                        eventDescription,
                        formatTimestamp(context, locale),
                        context.cooldownMinutes()
                },
                locale
        );
    }



    private String sensorMessageSegment(SensorType sensorType) {

        return sensorType.name().toLowerCase(Locale.ROOT);

    }


    private String formatNumber(double value, Locale locale) {

        NumberFormat formatter = NumberFormat.getNumberInstance(locale);
        formatter.setMinimumFractionDigits(1);
        formatter.setMaximumFractionDigits(2);
        return formatter.format(value);
    }




    private String formatTimestamp(Context context,Locale locale) {

        return DateTimeFormatter
                .ofPattern("dd-MM-yyyy HH:mm:ss z")
                .withLocale(locale)
                .format(context.recordedAt()
                        .atZone(context.recipient().timezone()));
    }



    private record DisplayValues(double reading,double threshold,String unitSymbol) {}




}
