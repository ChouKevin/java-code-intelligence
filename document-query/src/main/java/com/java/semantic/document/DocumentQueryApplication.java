package com.java.semantic.document;

import com.java.semantic.document.config.DocumentQueryProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(scanBasePackages = "com.java.semantic.document")
@EnableConfigurationProperties(DocumentQueryProperties.class)
public class DocumentQueryApplication {
    public static void main(String[] args) { SpringApplication.run(DocumentQueryApplication.class, args); }
}
