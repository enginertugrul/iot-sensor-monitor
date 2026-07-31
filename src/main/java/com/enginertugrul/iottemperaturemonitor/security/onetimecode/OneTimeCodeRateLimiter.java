package com.enginertugrul.iottemperaturemonitor.security.onetimecode;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;





public final class OneTimeCodeRateLimiter {

    private static final String UNKNOWN_KEY = "[unknown]";

    private final HmacDigest hmacDigest;
    private final OneTimeCodePolicy policy;
    private final String rateLimitPurposePrefix;
    private final String submissionScope;
    private final Map<String,WindowCounter> counters =
            new LinkedHashMap<>(16,0.75f,true);




    public OneTimeCodeRateLimiter(
            HmacDigest hmacDigest,
            OneTimeCodePolicy policy,
            String rateLimitPurposePrefix,
            String submissionScope
    ) {
        this.hmacDigest = Objects.requireNonNull(hmacDigest,"hmacDigest must not be null");

        this.policy = Objects.requireNonNull(policy,"policy must not be null");

        this.rateLimitPurposePrefix = requireText(rateLimitPurposePrefix,"rateLimitPurposePrefix");

        this.submissionScope = requireText(submissionScope,"submissionScope");
    }





    public synchronized boolean allowCodeIssue(String addressKey,String clientKey,Instant requestedAt) {

        Instant requiredRequestedAt = Objects.requireNonNull(requestedAt,"requestedAt must not be null");

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




    public synchronized boolean allowSubmission(String clientKey, Instant submittedAt) {

        Instant requiredSubmittedAt = Objects.requireNonNull(submittedAt,"submittedAt must not be null");

        purgeExpiredCounters(requiredSubmittedAt);

        return consume(
                submissionScope,
                normalizeKey(clientKey),
                policy.getMaximumSubmissionRequestsPerClient(),
                policy.getSubmissionRateLimitWindow(),
                requiredSubmittedAt
        );
    }






    private boolean consume(
            String scope,
            String rawKey,
            int maximumRequests,
            Duration window,
            Instant requestedAt
    ) {
        String protectedKey = hmacDigest.digest(rateLimitPurposePrefix + scope,rawKey);

        WindowCounter counter = counters.get(protectedKey);

        if (counter == null || !requestedAt.isBefore(counter.expiresAt)) {
            ensureCapacity();

            counters.put(
                    protectedKey,
                    new WindowCounter(1,requestedAt.plus(window))
            );

            return true;
        }

        if (counter.requestCount >= maximumRequests) {
            return false;
        }

        counter.requestCount++;
        return true;
    }




    private void purgeExpiredCounters(Instant requestedAt) {
        counters.entrySet().removeIf(
                entry -> !requestedAt.isBefore(entry.getValue().expiresAt)
        );
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
        return value == null || value.isBlank()
                ? UNKNOWN_KEY
                : value.trim();
    }




    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }

        return value;
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