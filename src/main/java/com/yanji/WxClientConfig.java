package com.yanji;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 按 app.wx.debug 选择 code2Session 实现：调试用固定桩，生产走微信接口。 */
@Configuration
public class WxClientConfig {
    @Bean
    @ConditionalOnProperty(name = "app.wx.debug", havingValue = "true")
    public WxCode2SessionClient debugClient(WxRunProperties props) {
        return new DebugWxCode2SessionClient(props);
    }

    @Bean
    @ConditionalOnProperty(name = "app.wx.debug", havingValue = "false", matchIfMissing = true)
    public WxCode2SessionClient wechatClient(WxRunProperties props) {
        return new WechatWxCode2SessionClient(props);
    }
}
