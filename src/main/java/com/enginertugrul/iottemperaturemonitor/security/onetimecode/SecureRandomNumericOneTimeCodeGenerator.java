package com.enginertugrul.iottemperaturemonitor.security.onetimecode;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;




public final class SecureRandomNumericOneTimeCodeGenerator implements OneTimeCodeGenerator {

    private static final int CODE_BOUND = 100_000_000;
    private static final Pattern CODE_PATTERN = Pattern.compile("[0-9]{8}");

    private final SecureRandom secureRandom = new SecureRandom();
    private final HmacDigest hmacDigest;
    private final String codePurpose;




    public SecureRandomNumericOneTimeCodeGenerator(HmacDigest hmacDigest,String codePurpose) {
        this.hmacDigest = Objects.requireNonNull(hmacDigest, "hmacDigest must not be null");

        if (codePurpose == null || codePurpose.isBlank()) {
            throw new IllegalArgumentException("codePurpose must not be blank");
        }

        this.codePurpose = codePurpose;
    }




    @Override
    public GeneratedOneTimeCode generate(Long subjectId) {
        long requiredSubjectId = requireSubjectId(subjectId);
        String rawCode = String.format(
                Locale.ROOT,
                "%08d",
                secureRandom.nextInt(CODE_BOUND)
        );

        String codeHash = hmacDigest.digest(codePurpose,requiredSubjectId + ":" + rawCode);

        return new GeneratedOneTimeCode(rawCode,codeHash);
    }



    @Override
    public boolean matches(Long subjectId, String rawCode, String codeHash) {

        if (subjectId == null || subjectId < 1 || rawCode == null) {
            return false;
        }

        String normalizedCode = rawCode.trim();

        if (!CODE_PATTERN.matcher(normalizedCode).matches()) {
            return false;
        }

        return hmacDigest.matches(codePurpose,subjectId + ":" + normalizedCode,codeHash);
    }




    private long requireSubjectId(Long subjectId) {
        if (subjectId == null || subjectId < 1) {
            throw new IllegalArgumentException("subjectId must be positive");
        }

        return subjectId;
    }





}