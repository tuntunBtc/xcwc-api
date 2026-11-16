package com.yanji;

/** Only a verified server-to-server WeChat result may supply the phone number. */
public interface WxPhoneNumberClient {
    record Phone(String countryCode, String nationalNumber) {
        public Phone {
            if (countryCode == null || !countryCode.matches("[1-9][0-9]{0,3}") ||
                nationalNumber == null || !nationalNumber.matches("[0-9]{6,14}") ||
                countryCode.length() + nationalNumber.length() > 15) {
                throw new IllegalArgumentException("Invalid verified phone format");
            }
        }
        public String e164() { return "+" + countryCode + nationalNumber; }
        public String masked() { return "+" + countryCode + " **** " + nationalNumber.substring(nationalNumber.length() - 4); }
        @Override public String toString() { return "Phone[redacted]"; }
    }
    Phone call(String phoneCode);
}
