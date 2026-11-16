package com.yanji;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** WeChat phone code exchange; app access tokens and raw upstream responses never leave this client. */
@Component
public class WechatPhoneNumberClient implements WxPhoneNumberClient {
    private final WxRunProperties props;
    private final RestTemplate http;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();
    private String cachedToken = "";
    private Instant tokenUntil = Instant.EPOCH;

    @org.springframework.beans.factory.annotation.Autowired
    public WechatPhoneNumberClient(WxRunProperties props) {
        this(props, newHttp(), Clock.systemUTC());
    }

    WechatPhoneNumberClient(WxRunProperties props, RestTemplate http, Clock clock) {
        this.props = props;
        this.http = http;
        this.clock = clock;
    }

    private static RestTemplate newHttp() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        return new RestTemplate(factory);
    }

    private RegistrationService.Failure unavailable() {
        return new RegistrationService.Failure(HttpStatus.BAD_GATEWAY, "WX_PHONE_UPSTREAM", "手机号验证暂不可用，请稍后重新授权");
    }

    private JsonNode post(URI uri, Map<String, ?> body) {
        try {
            JsonNode node = mapper.readTree(http.postForObject(uri, body, String.class));
            if (node == null || !node.isObject()) throw unavailable();
            return node;
        } catch (Exception error) {
            // Exceptions may contain access_token, AppSecret or an upstream phone number.
            throw unavailable();
        }
    }

    private static int errorCode(JsonNode node) {
        return node.has("errcode") ? node.path("errcode").asInt(-1) : 0;
    }

    private synchronized String accessToken(String rejectedToken) {
        if (clock.instant().isBefore(tokenUntil) && !cachedToken.isEmpty() && !cachedToken.equals(rejectedToken)) return cachedToken;
        JsonNode response = post(URI.create("https://api.weixin.qq.com/cgi-bin/stable_token"), Map.of(
            "grant_type", "client_credential", "appid", props.appId, "secret", props.secret,
            "force_refresh", rejectedToken != null));
        String token = response.path("access_token").asText("");
        long expires = response.path("expires_in").asLong(0);
        if (errorCode(response) != 0 || token.isBlank() || expires <= 0 || expires > 86400) throw unavailable();
        cachedToken = token;
        tokenUntil = clock.instant().plusSeconds(Math.max(0, expires - 60));
        return token;
    }

    private JsonNode exchange(String token, String phoneCode) {
        URI uri = UriComponentsBuilder.fromUriString("https://api.weixin.qq.com/wxa/business/getuserphonenumber")
            .queryParam("access_token", "{token}").encode().buildAndExpand(token).toUri();
        return post(uri, Map.of("code", phoneCode));
    }

    @Override public Phone call(String phoneCode) {
        if (!props.phoneLoginEnabled || props.debug || props.appId == null || props.appId.isBlank() || props.secret == null || props.secret.isBlank()) {
            throw new RegistrationService.Failure(HttpStatus.SERVICE_UNAVAILABLE, "WX_PHONE_UNCONFIGURED", "手机号登录尚未开放，请使用微信登录");
        }
        String token = accessToken(null);
        JsonNode response = exchange(token, phoneCode);
        if (errorCode(response) == 40014 || errorCode(response) == 42001) {
            response = exchange(accessToken(token), phoneCode); // Only retry an explicitly rejected app token once.
        }
        int code = errorCode(response);
        if (code == 40029 || code == 40163) {
            throw new RegistrationService.Failure(HttpStatus.UNAUTHORIZED, "WX_PHONE_CODE_INVALID", "手机号授权已失效，请重新点击授权");
        }
        if (code != 0) throw unavailable();
        JsonNode info = response.path("phone_info"), watermark = info.path("watermark");
        long timestamp = watermark.path("timestamp").asLong(0), now = clock.instant().getEpochSecond();
        if (!props.appId.equals(watermark.path("appid").asText()) || timestamp < now - 600 || timestamp > now + 60) throw unavailable();
        try {
            return new Phone(info.path("countryCode").asText(""), info.path("purePhoneNumber").asText(""));
        } catch (IllegalArgumentException invalid) {
            throw unavailable();
        }
    }
}
