package com.yanji;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 微信运动步数开放数据（wx.getWeRunData）的纯逻辑层：解密 + 解析 + 北京时间自然日归并与校验。
 *
 * <p>刻意不依赖 Spring / Jackson，以便在不具备完整构建链（如仅 JDK8）的环境中也能独立编译与单元测试。
 * 解密算法与微信官方一致：AES-128-CBC + PKCS#7（Java 侧为 PKCS5Padding，对 AES 等价），
 * key = Base64_Decode(session_key)（16 字节），iv = Base64_Decode(iv)。
 */
public final class WxRunCrypto {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final String TRANSFORM = "AES/CBC/PKCS5Padding";

    private WxRunCrypto() {}

    /** 单日步数：timestamp 为当日 0 点的秒级时间戳，step 为该日累计步数。 */
    public static final class DayStep {
        public final long timestamp;
        public final int step;
        public DayStep(long timestamp, int step) { this.timestamp = timestamp; this.step = step; }
    }

    /** 解密后的微信运动数据（仅抽取结算所需字段）。 */
    public static final class StepInfo {
        public final List<DayStep> stepInfoList = new ArrayList<>();
        public String watermarkAppId;
        public long watermarkTimestamp;
    }

    /** 用 session_key 解密微信运动 encryptedData，返回明文 JSON。 */
    public static String decrypt(String encryptedDataB64, String ivB64, String sessionKeyB64) throws GeneralSecurityException {
        byte[] key = Base64.getDecoder().decode(sessionKeyB64);
        byte[] iv = Base64.getDecoder().decode(ivB64);
        byte[] data = Base64.getDecoder().decode(encryptedDataB64);
        Cipher cipher = Cipher.getInstance(TRANSFORM);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return new String(cipher.doFinal(data), StandardCharsets.UTF_8);
    }

    /** 用 session_key 加密明文（仅用于测试构造向量，生产代码不应调用）。 */
    public static String encrypt(String plaintext, String ivB64, String sessionKeyB64) throws GeneralSecurityException {
        byte[] key = Base64.getDecoder().decode(sessionKeyB64);
        byte[] iv = Base64.getDecoder().decode(ivB64);
        byte[] data = plaintext.getBytes(StandardCharsets.UTF_8);
        Cipher cipher = Cipher.getInstance(TRANSFORM);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return Base64.getEncoder().encodeToString(cipher.doFinal(data));
    }

    /** 解析微信运动明文 JSON，抽取 stepInfoList 与 watermark。 */
    public static StepInfo parse(String plaintext) {
        Object root = Json.parse(plaintext);
        StepInfo info = new StepInfo();
        if (!(root instanceof java.util.Map)) throw new IllegalArgumentException("werun payload is not an object");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> obj = (java.util.Map<String, Object>) root;
        Object list = obj.get("stepInfoList");
        if (list instanceof List) {
            for (Object item : (List<?>) list) {
                if (item instanceof java.util.Map) {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> m = (java.util.Map<String, Object>) item;
                    Object ts = m.get("timestamp");
                    Object st = m.get("step");
                    if (ts instanceof Number && st instanceof Number) {
                        info.stepInfoList.add(new DayStep(((Number) ts).longValue(), ((Number) st).intValue()));
                    }
                }
            }
        }
        Object wm = obj.get("watermark");
        if (wm instanceof java.util.Map) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> w = (java.util.Map<String, Object>) wm;
            Object appId = w.get("appid");
            Object ts = w.get("timestamp");
            if (appId instanceof String) info.watermarkAppId = (String) appId;
            if (ts instanceof Number) info.watermarkTimestamp = ((Number) ts).longValue();
        }
        return info;
    }

    /** 取某个北京时间自然日（beijingDate）对应的累计步数；无对应日则返回 0。 */
    public static int stepsForDate(StepInfo info, LocalDate beijingDate) {
        int total = 0;
        for (DayStep day : info.stepInfoList) {
            LocalDate dayDate = Instant.ofEpochSecond(day.timestamp).atZone(SHANGHAI).toLocalDate();
            if (dayDate.equals(beijingDate)) {
                total += Math.max(0, day.step);
            }
        }
        return total;
    }

    /** 校验 watermark：appid 必须与自身一致，且时间戳不能来自未来、也不能过早（默认 7 天）。 */
    public static boolean verifyWatermark(StepInfo info, String expectedAppId, Clock clock, long maxAgeMillis) {
        if (info.watermarkAppId == null || !info.watermarkAppId.equals(expectedAppId)) return false;
        long nowSeconds = clock.instant().getEpochSecond();
        long ts = info.watermarkTimestamp;
        if (ts > nowSeconds + 300) return false; // 未来 5 分钟以上视为非法
        if (ts < nowSeconds - maxAgeMillis / 1000L) return false; // 过早视为非法
        return true;
    }

    // ---------- 极简 JSON 解析（仅支持本项目所需的子集，无外部依赖） ----------

    static final class Json {
        static Object parse(String s) {
            int[] p = {0};
            Object v = readValue(s, p);
            skipWs(s, p);
            return v;
        }
        private static Object readValue(String s, int[] p) {
            skipWs(s, p);
            if (p[0] >= s.length()) throw new IllegalArgumentException("unexpected end of json");
            char c = s.charAt(p[0]);
            switch (c) {
                case '{': return readObject(s, p);
                case '[': return readArray(s, p);
                case '"': return readString(s, p);
                case 't': case 'f': return readBoolean(s, p);
                case 'n': return readNull(s, p);
                default: return readNumber(s, p);
            }
        }
        private static java.util.Map<String, Object> readObject(String s, int[] p) {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            p[0]++; // {
            skipWs(s, p);
            if (peek(s, p) == '}') { p[0]++; return map; }
            while (true) {
                skipWs(s, p);
                String key = readString(s, p);
                skipWs(s, p);
                expect(s, p, ':');
                Object value = readValue(s, p);
                map.put(key, value);
                skipWs(s, p);
                char c = peek(s, p);
                if (c == ',') { p[0]++; continue; }
                if (c == '}') { p[0]++; break; }
                throw new IllegalArgumentException("expected , or } at " + p[0]);
            }
            return map;
        }
        private static List<Object> readArray(String s, int[] p) {
            List<Object> list = new ArrayList<>();
            p[0]++; // [
            skipWs(s, p);
            if (peek(s, p) == ']') { p[0]++; return list; }
            while (true) {
                list.add(readValue(s, p));
                skipWs(s, p);
                char c = peek(s, p);
                if (c == ',') { p[0]++; continue; }
                if (c == ']') { p[0]++; break; }
                throw new IllegalArgumentException("expected , or ] at " + p[0]);
            }
            return list;
        }
        private static String readString(String s, int[] p) {
            StringBuilder sb = new StringBuilder();
            p[0]++; // opening quote
            while (p[0] < s.length()) {
                char c = s.charAt(p[0]++);
                if (c == '"') break;
                if (c == '\\') {
                    char e = s.charAt(p[0]++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            String hex = s.substring(p[0], p[0] + 4);
                            p[0] += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                            break;
                        default: sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
        private static Number readNumber(String s, int[] p) {
            int start = p[0];
            while (p[0] < s.length()) {
                char c = s.charAt(p[0]);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') p[0]++;
                else break;
            }
            String num = s.substring(start, p[0]);
            if (num.contains(".") || num.contains("e") || num.contains("E")) return Double.parseDouble(num);
            try { return Long.parseLong(num); } catch (NumberFormatException e) { return Double.parseDouble(num); }
        }
        private static Boolean readBoolean(String s, int[] p) {
            if (s.startsWith("true", p[0])) { p[0] += 4; return Boolean.TRUE; }
            if (s.startsWith("false", p[0])) { p[0] += 5; return Boolean.FALSE; }
            throw new IllegalArgumentException("invalid boolean at " + p[0]);
        }
        private static Object readNull(String s, int[] p) {
            if (s.startsWith("null", p[0])) { p[0] += 4; return null; }
            throw new IllegalArgumentException("invalid null at " + p[0]);
        }
        private static void skipWs(String s, int[] p) {
            while (p[0] < s.length()) {
                char c = s.charAt(p[0]);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') p[0]++;
                else break;
            }
        }
        private static char peek(String s, int[] p) {
            skipWs(s, p);
            return p[0] < s.length() ? s.charAt(p[0]) : '\0';
        }
        private static void expect(String s, int[] p, char c) {
            skipWs(s, p);
            if (p[0] >= s.length() || s.charAt(p[0]) != c) throw new IllegalArgumentException("expected " + c + " at " + p[0]);
            p[0]++;
        }
    }
}
