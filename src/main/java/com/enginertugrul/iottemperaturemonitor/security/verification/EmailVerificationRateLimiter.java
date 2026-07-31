package com.enginertugrul.iottemperaturemonitor.security.verification;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Component
public class EmailVerificationRateLimiter {

    private static final String RATE_LIMIT_PURPOSE_PREFIX = "email-verification-rate-limit:";
    private static final String UNKNOWN_KEY = "[unknown]";

    private final EmailVerificationHmac emailVerificationHmac;
    private final EmailVerificationPolicy policy;
    private final Map<String,WindowCounter> counters = new LinkedHashMap<>(16,0.75f,true);

    public EmailVerificationRateLimiter(
            EmailVerificationHmac emailVerificationHmac,
            EmailVerificationPolicy policy
    ) {
        this.emailVerificationHmac = emailVerificationHmac;
        this.policy = policy;
    }

    public synchronized boolean allowCodeIssue(String addressKey, String clientKey, Instant requestedAt) {
        Instant requiredRequestedAt = Objects.requireNonNull(requestedAt, "requestedAt must not be null");
        purgeExpiredCounters(requiredRequestedAt);

        boolean addressAllowed = consume(
                "issue-address",
                normalizeKey(addressKey),
                policy.getMaximumIssueRequestsPerAddress(),
                policy.getIssueRateLimitWindow(),
                requiredRequestedAt
        );

        boolean clientAllowed = consume(
                "issue-client",
                normalizeKey(clientKey),
                policy.getMaximumIssueRequestsPerClient(),
                policy.getIssueRateLimitWindow(),
                requiredRequestedAt
        );

        return addressAllowed && clientAllowed;
    }

    public synchronized boolean allowVerification(String clientKey, Instant requestedAt) {
        Instant requiredRequestedAt = Objects.requireNonNull(requestedAt, "requestedAt must not be null");
        purgeExpiredCounters(requiredRequestedAt);

        return consume(
                "verification-client",
                normalizeKey(clientKey),
                policy.getMaximumVerificationRequestsPerClient(),
                policy.getVerificationRateLimitWindow(),
                requiredRequestedAt
        );
    }

    private boolean consume(String scope, String rawKey, int maximumRequests, Duration window, Instant requestedAt) {
        String protectedKey = emailVerificationHmac.digest(RATE_LIMIT_PURPOSE_PREFIX + scope,rawKey);
        WindowCounter counter = counters.get(protectedKey);

        if (counter == null || !requestedAt.isBefore(counter.expiresAt)) {
            ensureCapacity();
            counters.put(protectedKey,new WindowCounter(1,requestedAt.plus(window)));
            return true;
        }

        if (counter.requestCount >= maximumRequests) {
            return false;
        }

        counter.requestCount++;
        return true;
    }

    private void purgeExpiredCounters(Instant requestedAt) {
        counters.entrySet().removeIf(entry -> !requestedAt.isBefore(entry.getValue().expiresAt));
    }

    private void ensureCapacity() {
        while (counters.size() >= policy.getMaximumTrackedRateLimitKeys()) {
            Iterator<String> iterator = counters.keySet().iterator();

            if (!iterator.hasNext()) {
                return;
            }

            iterator.next();
            iterator.remove();
        }
    }

    private String normalizeKey(String value) {
        return value == null || value.isBlank() ? UNKNOWN_KEY : value.trim();
    }

    private static final class WindowCounter {

        private int requestCount;
        private final Instant expiresAt;

        private WindowCounter(int requestCount, Instant expiresAt) {
            this.requestCount = requestCount;
            this.expiresAt = expiresAt;
        }
    }
}