package com.enginertugrul.iotsensormonitor.service.notification.alert;

import com.enginertugrul.iotsensormonitor.entity.alert.ComparisonOperator;
import com.enginertugrul.iotsensormonitor.entity.measurement.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.entity.user.PreferredLanguage;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.service.alert.AlertTriggeredEvent;
import com.enginertugrul.iotsensormonitor.support.temperature.TemperatureUnitConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;



@ExtendWith(MockitoExtension.class)
class EmailAlertNotificationSenderTest {

    private static final String FROM = "notifications@example.com";
    private static final String RECIPIENT = "owner@example.com";
    private static final Instant RECORDED_AT = Instant.parse("2026-01-15T22:30:45Z");

    @Mock
    private ObjectProvider<JavaMailSender> mailSenderProvider;

    @Mock
    private JavaMailSender mailSender;

    @Captor
    private ArgumentCaptor<SimpleMailMessage> messageCaptor;

    private final MessageSource messageSource = messageSource();



    @ParameterizedTest
    @CsvSource(value = {
            "ENGLISH|CELSIUS|25.25 °C|20.0 °C",
            "ENGLISH|FAHRENHEIT|77.45 °F|68.0 °F",
            "ENGLISH|KELVIN|298.4 K|293.15 K",
            "TURKISH|CELSIUS|25,25 °C|20,0 °C",
            "TURKISH|FAHRENHEIT|77,45 °F|68,0 °F",
            "TURKISH|KELVIN|298,4 K|293,15 K"
    },delimiter = '|')
    void localizesTemperatureAlertsAndConvertsBothValues(
            PreferredLanguage language,TemperatureUnit unit,String reading,String threshold) {
        AlertTriggeredEvent event = event(SensorType.TEMPERATURE,language,unit,RECORDED_AT,
                new AlertTriggeredEvent.NumericThresholdTrigger(ComparisonOperator.ABOVE,25.25,20.0,MeasurementUnit.C));

        SimpleMailMessage message = sendAndCapture(event);

        assertCommonContent(message,language);
        if (language == PreferredLanguage.TURKISH) {
            assertThat(message.getSubject()).isEqualTo("Sıcaklık uyarısı: Çatı sensörü");
            assertThat(message.getText()).contains(
                    "\nKoşul: Eşik değerinin üstünde\n",
                    "\nÖlçülen sıcaklık: " + reading + "\n",
                    "\nEşik değeri: " + threshold + "\n");
        } else {
            assertThat(message.getSubject()).isEqualTo("Temperature alert: Çatı sensörü");
            assertThat(message.getText()).contains(
                    "\nCondition: Above threshold\n",
                    "\nMeasured temperature: " + reading + "\n",
                    "\nThreshold: " + threshold + "\n");
        }
    }



    @ParameterizedTest
    @CsvSource(value = {
            "ENGLISH|45.68 % RH|50.0 % RH",
            "TURKISH|45,68 % RH|50,0 % RH"
    },delimiter = '|')
    void localizesHumidityWithoutApplyingTemperaturePreferences(
            PreferredLanguage language,String reading,String threshold) {
        AlertTriggeredEvent event = event(SensorType.HUMIDITY,language,TemperatureUnit.FAHRENHEIT,RECORDED_AT,
                new AlertTriggeredEvent.NumericThresholdTrigger(ComparisonOperator.BELOW,45.678,50.0,MeasurementUnit.PERCENT));

        SimpleMailMessage message = sendAndCapture(event);

        assertCommonContent(message,language);
        assertThat(message.getText()).doesNotContain("°C","°F");
        if (language == PreferredLanguage.TURKISH) {
            assertThat(message.getSubject()).isEqualTo("Nem uyarısı: Çatı sensörü");
            assertThat(message.getText()).contains(
                    "\nKoşul: Eşik değerinin altında\n",
                    "\nÖlçülen bağıl nem: " + reading + "\n",
                    "\nEşik değeri: " + threshold + "\n");
        } else {
            assertThat(message.getSubject()).isEqualTo("Humidity alert: Çatı sensörü");
            assertThat(message.getText()).contains(
                    "\nCondition: Below threshold\n",
                    "\nMeasured relative humidity: " + reading + "\n",
                    "\nThreshold: " + threshold + "\n");
        }
    }



    @ParameterizedTest
    @EnumSource(PreferredLanguage.class)
    void localizesMotionAlertsWithoutNumericThresholdContent(PreferredLanguage language) {
        AlertTriggeredEvent event = event(SensorType.MOTION,language,TemperatureUnit.KELVIN,RECORDED_AT,
                new AlertTriggeredEvent.MotionDetectedTrigger());

        SimpleMailMessage message = sendAndCapture(event);

        assertCommonContent(message,language);
        assertThat(message.getText()).doesNotContain("Threshold:","Eşik değeri:","% RH","°C","°F");
        if (language == PreferredLanguage.TURKISH) {
            assertThat(message.getSubject()).isEqualTo("Hareket uyarısı: Çatı sensörü");
            assertThat(message.getText()).contains("\nOlay: Hareket algılandı\n");
        } else {
            assertThat(message.getSubject()).isEqualTo("Motion alert: Çatı sensörü");
            assertThat(message.getText()).contains("\nEvent: Motion detected\n");
        }
    }



    @ParameterizedTest
    @CsvSource(value = {
            "2026-11-01T05:30:00Z|01-11-2026 08:30:00 +03:00 [Europe/Istanbul]|01-11-2026 01:30:00 -04:00 [America/New_York]",
            "2026-11-01T06:30:00Z|01-11-2026 09:30:00 +03:00 [Europe/Istanbul]|01-11-2026 01:30:00 -05:00 [America/New_York]"
    },delimiter = '|')
    void includesOffsetsThatDistinguishRepeatedLocalTimesAtDstEnd(
            String recordedAt,String sensorTime,String recipientTime) {
        AlertTriggeredEvent event = event(SensorType.MOTION,PreferredLanguage.ENGLISH,TemperatureUnit.CELSIUS,
                Instant.parse(recordedAt),new AlertTriggeredEvent.MotionDetectedTrigger());

        SimpleMailMessage message = sendAndCapture(event);

        assertThat(message.getText()).contains(
                "\nSensor time: " + sensorTime + "\n",
                "\nTime in your preferred timezone: " + recipientTime + "\n");
    }



    @Test
    void skipsDeliveryWhenAlertsAreDisabled() {
        assertThatCode(() -> sender(false).send(motionEvent())).doesNotThrowAnyException();

        verifyNoInteractions(mailSenderProvider,mailSender);
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void rejectsMissingEventEvenWhenDeliveryIsDisabled(boolean enabled) {
        assertThatThrownBy(() -> sender(enabled).send(null))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("event must not be null");

        verifyNoInteractions(mailSenderProvider,mailSender);
    }



    @Test
    void reportsUnavailableMailSender() {
        assertThatThrownBy(() -> sender(true).send(motionEvent()))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("JavaMailSender is unavailable");

        verify(mailSenderProvider).getIfAvailable();
        verifyNoInteractions(mailSender);
    }



    @Test
    void propagatesTransportFailureWithoutRetryingInsideTheSender() {
        MailSendException failure = new MailSendException("Transport unavailable");
        when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);
        doThrow(failure).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> sender(true).send(motionEvent())).isSameAs(failure);

        verify(mailSender).send(any(SimpleMailMessage.class));
        verifyNoMoreInteractions(mailSender);
    }



    private SimpleMailMessage sendAndCapture(AlertTriggeredEvent event) {
        when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);

        sender(true).send(event);

        verify(mailSender).send(messageCaptor.capture());
        verifyNoMoreInteractions(mailSender);
        SimpleMailMessage message = messageCaptor.getValue();
        assertThat(message.getFrom()).isEqualTo(FROM);
        assertThat(message.getTo()).containsExactly(RECIPIENT);
        return message;
    }



    private static void assertCommonContent(SimpleMailMessage message,PreferredLanguage language) {
        if (language == PreferredLanguage.TURKISH) {
            assertThat(message.getText())
                    .startsWith("Bir uyarı kuralı tetiklendi.\n\n")
                    .contains(
                            "\nSensör: Çatı sensörü\n",
                            "\nKurulum konumu: Pencere\n",
                            "\nKonum: İstanbul, Kadıköy\n",
                            "\nSensör zamanı: 16-01-2026 01:30:45 +03:00 [Europe/Istanbul]\n",
                            "\nTercih ettiğiniz saat dilimindeki zaman: 15-01-2026 17:30:45 -05:00 [America/New_York]\n",
                            "\nBekleme süresi: 15 dakika\n")
                    .endsWith("Bu e-posta, bu sensör için uyarılar etkin olduğu için gönderildi.");
        } else {
            assertThat(message.getText())
                    .startsWith("An alert rule has been triggered.\n\n")
                    .contains(
                            "\nSensor: Çatı sensörü\n",
                            "\nInstallation location: Pencere\n",
                            "\nLocation: İstanbul, Kadıköy\n",
                            "\nSensor time: 16-01-2026 01:30:45 +03:00 [Europe/Istanbul]\n",
                            "\nTime in your preferred timezone: 15-01-2026 17:30:45 -05:00 [America/New_York]\n",
                            "\nCooldown: 15 minutes\n")
                    .endsWith("This email was sent because alerts are enabled for this sensor.");
        }
    }



    private EmailAlertNotificationSender sender(boolean enabled) {
        return new EmailAlertNotificationSender(mailSenderProvider,messageSource,new TemperatureUnitConverter(),enabled,FROM);
    }



    private static AlertTriggeredEvent motionEvent() {
        return event(SensorType.MOTION,PreferredLanguage.ENGLISH,TemperatureUnit.CELSIUS,RECORDED_AT,
                new AlertTriggeredEvent.MotionDetectedTrigger());
    }



    private static AlertTriggeredEvent event(
            SensorType type,PreferredLanguage language,TemperatureUnit unit,Instant recordedAt,AlertTriggeredEvent.Trigger trigger) {
        AlertTriggeredEvent.RecipientSnapshot recipient =
                new AlertTriggeredEvent.RecipientSnapshot(RECIPIENT,language,unit,ZoneId.of("America/New_York"));
        AlertTriggeredEvent.SensorSnapshot sensor =
                new AlertTriggeredEvent.SensorSnapshot(100L,type,"Çatı sensörü","Pencere","İstanbul","Kadıköy",ZoneId.of("Europe/Istanbul"));
        AlertTriggeredEvent.Context context = new AlertTriggeredEvent.Context(200L,recipient,sensor,recordedAt,15);
        return new AlertTriggeredEvent(context,trigger);
    }



    private static MessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultCharset(StandardCharsets.UTF_8);
        source.setFallbackToSystemLocale(false);
        return source;
    }
}