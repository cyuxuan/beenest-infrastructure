package club.beenest.payment.paymentorder.strategy.wechat;

import club.beenest.payment.paymentorder.config.PaymentConfig;
import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.core.RSAAutoCertificateConfig;
import com.wechat.pay.java.core.RSAPublicKeyConfig;
import com.wechat.pay.java.core.util.PemUtil;
import com.wechat.pay.java.service.payments.app.AppServiceExtension;
import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import com.wechat.pay.java.service.refund.RefundService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * 微信支付配置
 * 初始化微信支付SDK配置
 * 
 * @author System
 * @since 2026-01-26
 */
@Configuration
@Slf4j
public class WechatPayConfig {
    
    @Autowired
    private PaymentConfig paymentConfig;

    @Autowired
    private ResourceLoader resourceLoader;
    
    /**
     * 创建微信支付配置
     * 支持两种模式：
     * 1. 微信支付公钥模式 (推荐) - 使用 RSAPublicKeyConfig
     * 2. 平台证书模式 (旧版) - 使用 RSAAutoCertificateConfig 自动更新平台证书
     */
    @Bean
    public Config wechatPaySdkConfig() {
        PaymentConfig.WechatConfig wechatConfig = paymentConfig.getWechat();
        
        // 检查是否启用
        if (!wechatConfig.isEnabled()) {
            log.info("微信支付未启用，跳过配置初始化");
            return null;
        }
        
        // 检查必要配置是否完整
        if (!isConfigComplete(wechatConfig)) {
            log.warn("微信支付配置不完整，跳过初始化。需要配置: app-id, mch-id, api-v3-key, serial-no, private-key-path");
            return null;
        }
        
        try {
            log.info("初始化微信支付配置 - merchantId: {}, appId: {}", 
                    wechatConfig.getMchId(), wechatConfig.getAppId());

            // 1. 获取商户私钥
            PrivateKey privateKey = loadMerchantPrivateKey(wechatConfig.getPrivateKeyPath());
            
            // 2. 判断是否使用微信支付公钥模式
            if (StringUtils.hasText(wechatConfig.getPublicKeyPath()) && StringUtils.hasText(wechatConfig.getPublicKeyId())) {
                log.info("使用微信支付公钥模式初始化 - publicKeyId: {}", wechatConfig.getPublicKeyId());
                
                // 加载微信支付公钥
                PublicKey wechatPayPublicKey = loadWechatPayPublicKey(wechatConfig.getPublicKeyPath());
                
                return new RSAPublicKeyConfig.Builder()
                        .merchantId(wechatConfig.getMchId())
                        .privateKey(privateKey)
                        .merchantSerialNumber(wechatConfig.getSerialNo())
                        .publicKey(wechatPayPublicKey)
                        .publicKeyId(wechatConfig.getPublicKeyId())
                        .apiV3Key(wechatConfig.getApiV3Key())
                        .build();
            }

            // 3. 否则回退到平台证书模式 (旧版)
            log.info("使用平台证书模式初始化 (自动更新平台证书)");
            return new RSAAutoCertificateConfig.Builder()
                    .merchantId(wechatConfig.getMchId())
                    .privateKey(privateKey)
                    .merchantSerialNumber(wechatConfig.getSerialNo())
                    .apiV3Key(wechatConfig.getApiV3Key())
                    .build();
            
        } catch (Exception e) {
            log.error("微信支付配置初始化失败: {}，微信支付功能将不可用", e.getMessage());
            log.debug("微信支付配置初始化失败详情", e);
            return null;
        }
    }

    /**
     * 加载商户私钥
     * 支持 classpath: 路径
     */
    private PrivateKey loadMerchantPrivateKey(String privateKeyPath) throws Exception {
        return loadKey(privateKeyPath, PemUtil::loadPrivateKeyFromPath);
    }

    /**
     * 加载微信支付公钥
     * 支持 classpath: 路径
     */
    private PublicKey loadWechatPayPublicKey(String publicKeyPath) throws Exception {
        return loadKey(publicKeyPath, PemUtil::loadPublicKeyFromPath);
    }

    /**
     * 通用加载 Key 方法
     */
    private <T> T loadKey(String path, KeyLoader<T> loader) throws Exception {
        if (path.startsWith("classpath:")) {
            Resource resource = resourceLoader.getResource(path);
            try {
                // 尝试直接获取文件路径（适用于本地开发环境）
                return loader.load(resource.getFile().getAbsolutePath());
            } catch (IOException e) {
                // 如果在 JAR 中运行，需要将资源拷贝到临时文件
                log.info("从 classpath 加载证书到临时文件: {}", path);
                File tempFile = File.createTempFile("wechat_pay_cert_", ".pem");
                tempFile.deleteOnExit();
                try (InputStream is = resource.getInputStream();
                     OutputStream os = new FileOutputStream(tempFile)) {
                    is.transferTo(os);
                }
                return loader.load(tempFile.getAbsolutePath());
            }
        } else {
            return loader.load(path);
        }
    }

    @FunctionalInterface
    private interface KeyLoader<T> {
        T load(String path) throws Exception;
    }
    
    /**
     * 检查微信支付配置是否完整
     */
    private boolean isConfigComplete(PaymentConfig.WechatConfig config) {
        return config.getAppId() != null && !config.getAppId().isEmpty()
                && config.getMchId() != null && !config.getMchId().isEmpty()
                && config.getApiV3Key() != null && !config.getApiV3Key().isEmpty()
                && config.getSerialNo() != null && !config.getSerialNo().isEmpty()
                && config.getPrivateKeyPath() != null && !config.getPrivateKeyPath().isEmpty();
    }
    
    /**
     * 创建JSAPI支付服务
     */
    @Bean
    public JsapiServiceExtension jsapiService() {
        // 先尝试获取Config bean
        Config config = wechatPaySdkConfig();
        
        if (config == null) {
            log.info("微信支付配置为空，跳过JSAPI服务初始化");
            return null;
        }
        
        try {
            JsapiServiceExtension service = new JsapiServiceExtension.Builder()
                    .config(config)
                    .build();
            
            log.info("微信JSAPI支付服务初始化成功");
            return service;
            
        } catch (Exception e) {
            log.error("微信JSAPI支付服务初始化失败: {}，微信支付功能将不可用", e.getMessage());
            log.debug("微信JSAPI支付服务初始化失败详情", e);
            return null;
        }
    }

    /**
     * 创建微信 App 支付服务。
     */
    @Bean
    public AppServiceExtension appService() {
        Config config = wechatPaySdkConfig();

        if (config == null) {
            log.info("微信支付配置为空，跳过 App 支付服务初始化");
            return null;
        }

        try {
            AppServiceExtension service = new AppServiceExtension.Builder()
                    .config(config)
                    .build();

            log.info("微信 App 支付服务初始化成功");
            return service;

        } catch (Exception e) {
            log.error("微信 App 支付服务初始化失败: {}，微信 App 支付功能将不可用", e.getMessage());
            log.debug("微信 App 支付服务初始化失败详情", e);
            return null;
        }
    }

    /**
     * 创建微信退款服务
     */
    @Bean
    public RefundService refundService() {
        Config config = wechatPaySdkConfig();

        if (config == null) {
            log.info("微信支付配置为空，跳过退款服务初始化");
            return null;
        }

        try {
            RefundService service = new RefundService.Builder()
                    .config(config)
                    .build();

            log.info("微信退款服务初始化成功");
            return service;

        } catch (Exception e) {
            log.error("微信退款服务初始化失败: {}，微信退款功能将不可用", e.getMessage());
            log.debug("微信退款服务初始化失败详情", e);
            return null;
        }
    }
}
