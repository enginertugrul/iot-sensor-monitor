package com.enginertugrul.iotsensormonitor.security.onetimecode;

public interface OneTimeCodeGenerator {

    GeneratedOneTimeCode generate(Long subjectId);

    boolean matches(Long subjectId, String rawCode, String codeHash);

}