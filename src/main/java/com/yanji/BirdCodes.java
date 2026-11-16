package com.yanji;

import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/** External manufacturing codes, never user IDs or authentication credentials. */
public final class BirdCodes {
    public static final String REGEX = "[A-Z]{2}-[A-Z][A-Z0-9]{1,7}-[1-9][0-9]{0,18}";
    private static final Pattern FORMAT = Pattern.compile(REGEX);
    private BirdCodes() {}
    public static String validate(String code) {
        try {
            if (code != null && code.length() <= 32 && FORMAT.matcher(code).matches()) {
                Long.parseLong(code.substring(code.lastIndexOf('-') + 1));
                return code;
            }
        } catch (NumberFormatException ignored) { /* Values above signed 64-bit are invalid. */ }
        throw new RegistrationService.Failure(HttpStatus.BAD_REQUEST, "INVALID_BIRD_CODE", "小鸟编码应为大写城市码-IP CODE-正整数雪花ID，总长不超过 32 位");
    }
}
