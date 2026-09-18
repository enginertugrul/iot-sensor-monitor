package com.enginertugrul.iotsensormonitor.service.notification.retry;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.SendFailedException;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;

import javax.net.ssl.SSLHandshakeException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;

@Component
public class EmailRetryFailureClassifier {

    public boolean isRetryable(Throwable failure) {
        if (!(failure instanceof MailSendException)) {
            return false;
        }

        Deque<Throwable> pending = new ArrayDeque<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(failure);

        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();

            if (!visited.add(current)) {
                continue;
            }

            if (isNonRetryable(current)) {
                return false;
            }

            if (current instanceof MailSendException mailFailure) {
                Collections.addAll(pending,mailFailure.getMessageExceptions());
            }

            Throwable cause = current.getCause();

            if (cause != null) {
                pending.addLast(cause);
            }
        }

        return true;
    }

    private boolean isNonRetryable(Throwable failure) {
        if (failure instanceof MailAuthenticationException || failure instanceof AuthenticationFailedException
                || failure instanceof MailParseException || failure instanceof MailPreparationException
                || failure instanceof SSLHandshakeException || failure instanceof InterruptedException) {
            return true;
        }

        if (failure instanceof SendFailedException sendFailure
                && (!ObjectUtils.isEmpty(sendFailure.getInvalidAddresses()) || !ObjectUtils.isEmpty(sendFailure.getValidSentAddresses()))) {
            return true;
        }

        int returnCode = switch (failure) {
            case SMTPSendFailedException smtpFailure -> smtpFailure.getReturnCode();
            case SMTPAddressFailedException smtpFailure -> smtpFailure.getReturnCode();
            default -> 0;
        };

        return returnCode >= 500 && returnCode < 600;
    }
}