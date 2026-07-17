package com.java.semantic.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 啟用 JDT Language Server 設定綁定 */
@Configuration
@EnableConfigurationProperties(JdtLsProperties.class)
public class JdtLsConfiguration {
}
