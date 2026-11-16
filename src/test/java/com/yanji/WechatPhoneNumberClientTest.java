package com.yanji;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.io.IOException;
import java.time.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class WechatPhoneNumberClientTest {
    WxRunProperties props;
    MockRestServiceServer server;
    WechatPhoneNumberClient client;
    static final Instant NOW = Instant.parse("2026-11-14T00:00:00Z");
    @BeforeEach void setup() {
        props = new WxRunProperties(); props.appId = "wx-phone-fixture"; props.secret = "fake-app-secret"; props.phoneLoginEnabled = true;
        var http = new RestTemplate(); server = MockRestServiceServer.bindTo(http).build();
        client = new WechatPhoneNumberClient(props, http, Clock.fixed(NOW, ZoneOffset.UTC));
    }
    void token(String token, boolean force) {
        server.expect(requestTo("https://api.weixin.qq.com/cgi-bin/stable_token"))
            .andExpect(method(HttpMethod.POST)).andExpect(content().json("{\"grant_type\":\"client_credential\",\"appid\":\"wx-phone-fixture\",\"secret\":\"fake-app-secret\",\"force_refresh\":"+force+"}"))
            .andRespond(withSuccess("{\"access_token\":\""+token+"\",\"expires_in\":7200}", MediaType.APPLICATION_JSON));
    }
    String result(String app, long stamp) {
        return "{\"errcode\":0,\"phone_info\":{\"purePhoneNumber\":\"19900000000\",\"countryCode\":\"86\",\"watermark\":{\"appid\":\""+app+"\",\"timestamp\":"+stamp+"}}}";
    }
    void exchange(String token, String response) {
        server.expect(requestTo("https://api.weixin.qq.com/wxa/business/getuserphonenumber?access_token="+token))
            .andExpect(method(HttpMethod.POST)).andExpect(content().json("{\"code\":\"phone-code\"}"))
            .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }
    @Test void cachesServerTokenAndValidatesWatermark() {
        token("token-one", false); exchange("token-one", result(props.appId, NOW.getEpochSecond())); exchange("token-one", result(props.appId, NOW.getEpochSecond()));
        assertThat(client.call("phone-code").e164()).isEqualTo("+8619900000000");
        assertThat(client.call("phone-code").masked()).isEqualTo("+86 **** 0000"); server.verify();
    }
    @Test void refreshesOnlyExplicitlyInvalidTokenOnce() {
        token("old", false); exchange("old", "{\"errcode\":40014}"); token("new", true); exchange("new", result(props.appId,NOW.getEpochSecond()));
        assertThat(client.call("phone-code").toString()).isEqualTo("Phone[redacted]"); server.verify();
    }
    @Test void rejectsReplayedPhoneCodeWithoutRetry() {
        token("one", false); exchange("one", "{\"errcode\":40163,\"errmsg\":\"private upstream data\"}");
        assertThatThrownBy(() -> client.call("phone-code")).isInstanceOfSatisfying(RegistrationService.Failure.class,
            e -> { assertThat(e.code).isEqualTo("WX_PHONE_CODE_INVALID"); assertThat(e.getMessage()).doesNotContain("private"); }); server.verify();
    }
    @Test void rejectsWrongAppAndStaleWatermark() {
        token("one", false); exchange("one", result("other-app", NOW.getEpochSecond())); exchange("one", result(props.appId,NOW.minusSeconds(601).getEpochSecond()));
        for (int i=0;i<2;i++) assertThatThrownBy(() -> client.call("phone-code")).isInstanceOfSatisfying(RegistrationService.Failure.class, e -> assertThat(e.code).isEqualTo("WX_PHONE_UPSTREAM"));
        server.verify();
    }
    @Test void doesNotLeakNetworkExceptionsOrCallProductionFromDebug() {
        server.expect(requestTo("https://api.weixin.qq.com/cgi-bin/stable_token")).andRespond(withException(new IOException("fake-app-secret access_token=secret 19900000000")));
        assertThatThrownBy(() -> client.call("phone-code")).hasMessage("手机号验证暂不可用，请稍后重新授权"); server.verify();
        props.debug=true;
        assertThatThrownBy(() -> client.call("phone-code")).isInstanceOfSatisfying(RegistrationService.Failure.class, e -> assertThat(e.code).isEqualTo("WX_PHONE_UNCONFIGURED"));
    }
}
