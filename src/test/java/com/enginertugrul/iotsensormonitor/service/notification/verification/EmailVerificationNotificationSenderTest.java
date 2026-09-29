package com.enginertugrul.iotsensormonitor.service.notification.verification;

import com.enginertugrul.iotsensormonitor.entity.user.PreferredLanguage;
import com.enginertugrul.iotsensormonitor.service.user.verification.EmailVerificationCodeDelivery;
import com.enginertugrul.iotsensormonitor.service.user.verification.EmailVerificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

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
class EmailVerificationNotificationSenderTest {

    private static final String FROM = "notifications@example.com";
    private static final String RECIPIENT = "owner@example.com";
    private static final String CODE = "00123456";
    private static final Instant NOW = Instant.parse("2026-01-15T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW,ZoneOffset.UTC);

    @Mock
    private ObjectProvider<JavaMailSender> mailSenderProvider;

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private EmailVerificationService verificationService;

    @Captor
    private ArgumentCaptor<SimpleMailMessage> messageCaptor;

    private final MessageSource messageSource = messageSource();



    @ParameterizedTest
    @EnumSource(PreferredLanguage.class)
    void sendsLocalizedCodeAndRemainingLifetimeWithNormalizedAddresses(PreferredLanguage language) {
        EmailVerificationCodeDelivery delivery = delivery(language,CODE,NOW.plusSeconds(121));
        allowDelivery(delivery);
        EmailVerificationNotificationSender sender =
                new EmailVerificationNotificationSender(mailSenderProvider,messageSource,verificationService,true,"  " + FROM + "  ",CLOCK);

        sender.send(delivery);

        SimpleMailMessage message = capturedMessage();
        assertThat(message.getFrom()).isEqualTo(FROM);
        assertThat(message.getTo()).containsExactly(RECIPIENT);
        assertThat(message.getText()).contains("\n\n00123456\n\n");
        if (language == PreferredLanguage.TURKISH) {
            assertThat(message.getSubject()).isEqualTo("IoT Sensör İzleme doğrulama kodunuz");
            assertThat(message.getText())
                    .startsWith("IoT Sensör İzleme hesabınızı doğrulamak için aşağıdaki e-posta doğrulama kodunu kullanın:\n\n")
                    .contains(
                            "Bu kod 3 dakika içinde geçerliliğini yitirir.",
                            "Yalnızca en son istediğiniz kod geçerlidir.",
                            "Hesap oluşturmadıysanız veya yeni bir kod istemediyseniz bu e-postayı dikkate almayabilirsiniz.")
                    .endsWith("Güvenliğiniz için bu kodu hiç kimseyle paylaşmayın.");
        } else {
            assertThat(message.getSubject()).isEqualTo("Your IoT Sensor Monitor verification code");
            assertThat(message.getText())
                    .startsWith("Use the following verification code to confirm your email address for IoT Sensor Monitor:\n\n")
                    .contains(
                            "This code expires in 3 minutes.",
                            "Only the most recently requested code will work.",
                            "If you did not create an account or request a new code, you can safely ignore this email.")
                    .endsWith("For your security, do not share this code with anyone.");
        }
        verify(verificationService).canDeliverCode(delivery);
    }



    @ParameterizedTest
    @CsvSource({"1,1","59,1","60,1","61,2","119,2","120,2","121,3"})
    void roundsPositiveRemainingSecondsUpToMinutes(long remainingSeconds,long expectedMinutes) {
        EmailVerificationCodeDelivery delivery = delivery(PreferredLanguage.ENGLISH,CODE,NOW.plusSeconds(remainingSeconds));
        allowDelivery(delivery);

        sender(true).send(delivery);

        assertThat(capturedMessage().getText()).contains("This code expires in " + expectedMinutes + " minutes.");
    }



    @ParameterizedTest
    @ValueSource(longs = {-1000,0,1,999})
    void suppressesExpiredCodesAndCodesWithLessThanOneWholeSecondRemaining(long remainingMillis) {
        EmailVerificationCodeDelivery delivery = delivery(PreferredLanguage.ENGLISH,CODE,NOW.plusMillis(remainingMillis));
        when(verificationService.canDeliverCode(delivery)).thenReturn(true);

        assertThatCode(() -> sender(true).send(delivery)).doesNotThrowAnyException();

        verify(verificationService).canDeliverCode(delivery);
        verifyNoInteractions(mailSenderProvider,mailSender);
    }



    @Test
    void skipsEligibilityAndMailLookupWhenDeliveryIsDisabled() {
        EmailVerificationCodeDelivery delivery = delivery(PreferredLanguage.ENGLISH,CODE,NOW.plusSeconds(600));

        assertThatCode(() -> sender(false).send(delivery)).doesNotThrowAnyException();

        verifyNoInteractions(verificationService,mailSenderProvider,mailSender);
    }



    @Test
    void skipsAStaleCodeAndSendsTheCurrentCodeWhenEligibilityAllowsIt() {
        EmailVerificationCodeDelivery stale = delivery(PreferredLanguage.ENGLISH,CODE,NOW.plusSeconds(600));
        EmailVerificationCodeDelivery current = delivery(PreferredLanguage.ENGLISH,"00876543",NOW.plusSeconds(900));
        when(verificationService.canDeliverCode(stale)).thenReturn(false);
        allowDelivery(current);
        EmailVerificationNotificationSender sender = sender(true);

        sender.send(stale);

        verifyNoInteractions(mailSenderProvider,mailSender);

        sender.send(current);

        assertThat(capturedMessage().getText()).contains("\n\n00876543\n\n").doesNotContain(CODE);
        verify(verificationService).canDeliverCode(stale);
        verify(verificationService).canDeliverCode(current);
        verifyNoMoreInteractions(verificationService);
    }



    @Test
    void reportsUnavailableMailSenderForAnEligibleUnexpiredCode() {
        EmailVerificationCodeDelivery delivery = delivery(PreferredLanguage.ENGLISH,CODE,NOW.plusSeconds(600));
        when(verificationService.canDeliverCode(delivery)).thenReturn(true);

        assertThatThrownBy(() -> sender(true).send(delivery))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("JavaMailSender is unavailable");

        verify(verificationService).canDeliverCode(delivery);
        verify(mailSenderProvider).getIfAvailable();
        verifyNoInteractions(mailSender);
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void rejectsMissingDeliveryEvenWhenDeliveryIsDisabled(boolean enabled) {
        assertThatThrownBy(() -> sender(enabled).send(null))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("delivery must not be null");

        verifyNoInteractions(verificationService,mailSenderProvider,mailSender);
    }



    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ","\t","\n"})
    void rejectsBlankFromAddress(String fromAddress) {
        assertThatThrownBy(() -> new EmailVerificationNotificationSender(
                mailSenderProvider,messageSource,verificationService,true,fromAddress,CLOCK))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("fromAddress must not be blank");
    }



    @Test
    void propagatesTransportFailureWithoutRetryingInsideTheSender() {
        EmailVerificationCodeDelivery delivery = delivery(PreferredLanguage.ENGLISH,CODE,NOW.plusSeconds(600));
        MailSendException failure = new MailSendException("Transport unavailable");
        allowDelivery(delivery);
        doThrow(failure).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> sender(true).send(delivery)).isSameAs(failure);

        verify(mailSender).send(any(SimpleMailMessage.class));
        verifyNoMoreInteractions(mailSender);
    }

    private void allowDelivery(EmailVerificationCodeDelivery delivery) {
        when(verificationService.canDeliverCode(delivery)).thenReturn(true);
        when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);
    }

    private SimpleMailMessage capturedMessage() {
        verify(mailSender).send(messageCaptor.capture());
        verifyNoMoreInteractions(mailSender);
        return messageCaptor.getValue();
    }

    private EmailVerificationNotificationSender sender(boolean enabled) {
        return new EmailVerificationNotificationSender(mailSenderProvider,messageSource,verificationService,enabled,FROM,CLOCK);
    }

    private static EmailVerificationCodeDelivery delivery(PreferredLanguage language,String code,Instant expiresAt) {
        return new EmailVerificationCodeDelivery(42L," OWNER@Example.COM ",language,code,expiresAt);
    }

    private static MessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultCharset(StandardCharsets.UTF_8);
        source.setFallbackToSystemLocale(false);
        return source;
    }
}