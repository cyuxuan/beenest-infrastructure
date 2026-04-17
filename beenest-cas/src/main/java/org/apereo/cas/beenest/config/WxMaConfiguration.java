package org.apereo.cas.beenest.config;

import cn.binarywang.wx.miniapp.api.WxMaService;
import cn.binarywang.wx.miniapp.api.impl.WxMaServiceImpl;
import cn.binarywang.wx.miniapp.config.impl.WxMaDefaultConfigImpl;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 微信小程序 SDK 配置
 * <p>
 * 仅在 beenest.miniapp.wechat.appid 配置存在时激活。
 */
@AutoConfiguration
@EnableConfigurationProperties(MiniAppProperties.class)
public class WxMaConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WxMaConfiguration.class);

    @Bean
    @ConditionalOnProperty(prefix = "beenest.miniapp.wechat", name = "appid")
    public WxMaService wxMaService(final MiniAppProperties miniAppProperties) {
        MiniAppProperties.WechatConfig config = miniAppProperties.getWechat();
        if (config == null || StringUtils.isBlank(config.getAppid()) || StringUtils.isBlank(config.getSecret())) {
            throw new IllegalStateException("微信小程序配置不完整，无法初始化 WxMaService");
        }

        WxMaDefaultConfigImpl wxConfig = new WxMaDefaultConfigImpl();
        wxConfig.setAppid(config.getAppid());
        wxConfig.setSecret(config.getSecret());

        WxMaService service = new WxMaServiceImpl();
        service.setWxMaConfig(wxConfig);
        log.info("已初始化微信小程序 WxMaService, appid={}", maskAppId(config.getAppid()));
        return service;
    }

    /**
     * 脱敏显示微信 appid，避免日志泄露完整凭证。
     *
     * @param appId 微信 appid
     * @return 脱敏后的 appid
     */
    private String maskAppId(String appId) {
        if (StringUtils.isBlank(appId) || appId.length() <= 8) {
            return "***";
        }
        return appId.substring(0, 4) + "****" + appId.substring(appId.length() - 4);
    }
}
