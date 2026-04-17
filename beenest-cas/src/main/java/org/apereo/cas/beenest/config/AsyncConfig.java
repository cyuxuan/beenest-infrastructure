package org.apereo.cas.beenest.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 异步任务配置
 * <p>
 * 启用 @Async 支持，用于审计日志等非阻塞异步写入。
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
