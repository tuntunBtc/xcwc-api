package com.yanji;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 生产用 code2Session：调用微信接口 https://api.weixin.qq.com/sns/jscode2session。
 * AppSecret 仅从配置（环境变量）读取，绝不下发或打印。session_key 拿到后只存入服务端 WxSessionStore。
 */
public class WechatWxCode2SessionClient implements WxCode2SessionClient {
    private final WxRunProperties props;
    private final RestTemplate http;
    private final ObjectMapper mapper = new ObjectMapper();

    public WechatWxCode2SessionClient(WxRunProperties props) {
        this.props = props;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        this.http = new RestTemplate(factory);
    }

    @Override
    public WechatSession call(String code) {
        if (props.appId.isBlank() || props.secret.isBlank()) {
            throw new RegistrationService.Failure(HttpStatus.SERVICE_UNAVAILABLE, "WX_LOGIN_UNCONFIGURED", "微信登录尚未开放");
        }
        URI uri = UriComponentsBuilder.fromUriString("https://api.weixin.qq.com/sns/jscode2session")
            .queryParam("appid", "{appid}")
            .queryParam("secret", "{secret}")
            .queryParam("js_code", "{code}")
            .queryParam("grant_type", "authorization_code")
            .encode().buildAndExpand(props.appId, props.secret, code).toUri();
        String body;
        try {
            body = http.getForObject(uri, String.class);
        } catch (Exception e) {
            throw new RegistrationService.Failure(HttpStatus.BAD_GATEWAY, "WX_LOGIN_UPSTREAM", "微信登录服务暂不可用");
        }
        try {
            JsonNode node = mapper.readTree(body);
            if (node.has("errcode") && node.get("errcode").asInt(0) != 0) {
                throw new RegistrationService.Failure(HttpStatus.UNAUTHORIZED, "WX_LOGIN_FAILED", "微信登录失败，请重试");
            }
            String openid = node.path("openid").asText(null);
            String sessionKey = node.path("session_key").asText(null);
            if (openid == null || openid.isBlank() || sessionKey == null || sessionKey.isBlank()) {
                throw new RegistrationService.Failure(HttpStatus.UNAUTHORIZED, "WX_LOGIN_FAILED", "微信登录返回数据不完整");
            }
            return new WechatSession(openid, sessionKey);
        } catch (RegistrationService.Failure f) {
            throw f;
        } catch (Exception e) {
            throw new RegistrationService.Failure(HttpStatus.BAD_GATEWAY, "WX_LOGIN_PARSE", "微信登录返回解析失败");
        }
    }
}
