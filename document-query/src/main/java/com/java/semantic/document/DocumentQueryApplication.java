package com.java.semantic.document;

import com.java.semantic.document.config.DocumentQueryProperties;
import com.java.semantic.document.local.DocumentLocalMain;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(scanBasePackages = "com.java.semantic.document")
@EnableConfigurationProperties(DocumentQueryProperties.class)
public class DocumentQueryApplication {
    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--document-local")) {
            DocumentLocalMain.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        SpringApplication.run(DocumentQueryApplication.class, args);
    }
}
