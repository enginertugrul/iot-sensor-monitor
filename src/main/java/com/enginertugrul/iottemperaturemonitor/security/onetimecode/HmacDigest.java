package com.enginertugrul.iottemperaturemonitor.security.onetimecode;

public interface HmacDigest {

    String digest(String purpose, String value);

    boolean matches(String purpose, String value, String expectedDigest);

}