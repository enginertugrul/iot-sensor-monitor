package com.enginertugrul.iotsensormonitor.support.web;




public final class EmailMasker {

    private EmailMasker() {}


    public static String mask(String email) {
        int separatorIndex = email.lastIndexOf('@');

        if (separatorIndex < 1) {
            return "***";
        }

        String localPart = email.substring(0,separatorIndex);
        String domainPart = email.substring(separatorIndex);

        if (localPart.length() == 1) {
            return localPart.charAt(0) + "***" + domainPart;
        }

        return localPart.charAt(0) + "***"
                + localPart.charAt(localPart.length() - 1)
                + domainPart;
    }
}