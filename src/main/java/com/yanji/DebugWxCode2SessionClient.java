package com.yanji;

import org.springframework.http.HttpStatus;

/** 调试用 code2Session：仅当 code 等于配置的调试码时返回固定 openid + session_key。供本地/CI 无真实微信凭证时联调。 */
public class DebugWxCode2SessionClient implements WxCode2SessionClient {
    private final WxRunProperties props;
    public DebugWxCode2SessionClient(WxRunProperties props) { this.props = props; }

    @Override
    public WechatSession call(String code) {
        if (!props.debugCode.equals(code)) {
            throw new RegistrationService.Failure(HttpStatus.UNAUTHORIZED, "WX_LOGIN_CODE_INVALID", "调试登录码无效");
        }
        return new WechatSession(props.debugOpenid, props.debugSessionKey);
    }
}
