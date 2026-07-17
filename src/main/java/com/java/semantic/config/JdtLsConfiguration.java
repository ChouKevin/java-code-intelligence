package com.java.semantic.config;

import com.java.semantic.semantic.adapter.jdtls.JdtLsProcessFactory;
import com.java.semantic.semantic.adapter.jdtls.JdtLsReadinessProbe;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 啟用 JDT Language Server 設定綁定並提供工作區協作元件 */
@Configuration
@EnableConfigurationProperties(JdtLsProperties.class)
public class JdtLsConfiguration {

    @Bean
    public JdtLsProcessFactory jdtLsProcessFactory(JdtLsProperties properties) {
        return new JdtLsProcessFactory(properties);
    }

    @Bean
    public JdtLsReadinessProbe jdtLsReadinessProbe(JdtLsProperties properties) {
        return new JdtLsReadinessProbe(properties);
    }
}
