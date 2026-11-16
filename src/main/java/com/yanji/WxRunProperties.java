package com.yanji;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 微信步数兑换相关配置（小程序 AppID、折算规则、调试开关）。敏感值（AppSecret）只从环境变量注入，绝不写死。 */
@Component
public class WxRunProperties {
    @Value("${app.wx.app-id:}") public String appId;
    @Value("${app.wx.app-secret:}") public String secret;
    @Value("${app.wx.phone-login-enabled:true}") public boolean phoneLoginEnabled;
    @Value("${app.wx.step-unit:1000}") public int stepUnit;
    @Value("${app.wx.step-daily-cap:5}") public int stepDailyCap;
    @Value("${app.wx.supply-enabled:false}") public boolean supplyEnabled;
    @Value("${app.wx.supply-steps:3000}") public int supplySteps;
    @Value("${app.wx.debug:false}") public boolean debug;
    @Value("${app.wx.debug-code:DEBUG_CODE}") public String debugCode;
    @Value("${app.wx.debug-openid:debug_openid}") public String debugOpenid;
    @Value("${app.wx.debug-session-key:}") public String debugSessionKey;

    /** watermark 允许的最大时差（默认 7 天）。 */
    public long watermarkMaxAgeMillis() {
        return 7L * 86400_000L;
    }
}
