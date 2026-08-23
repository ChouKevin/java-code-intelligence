package com.java.semantic.repository.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 啟用儲存庫設定綁定 */
@Configuration
@EnableConfigurationProperties(RepositoryProperties.class)
public class RepositoryConfiguration {
}
