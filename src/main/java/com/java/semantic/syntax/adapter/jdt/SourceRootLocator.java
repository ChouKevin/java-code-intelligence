package com.java.semantic.syntax.adapter.jdt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.util.CollectionUtils;

import lombok.extern.slf4j.Slf4j;

/** 解析 repository 的 Java source root 與 resource root，支援單模組與 Maven 多模組 */
@Slf4j
class SourceRootLocator {

    private static final String SRC_MAIN_JAVA = "src/main/java";

    private static final String SRC_MAIN_RESOURCES = "src/main/resources";

    private static final String AGGREGATOR_PACKAGING = "<packaging>pom</packaging>";

    private static final Pattern MODULE_PATTERN = Pattern.compile("<module>\\s*([^<]+)\\s*</module>");

    List<Path> sourceRootsOf(Path repositoryRoot) {
        return rootsOf(repositoryRoot, SRC_MAIN_JAVA);
    }

    List<Path> resourceRootsOf(Path repositoryRoot) {
        return rootsOf(repositoryRoot, SRC_MAIN_RESOURCES);
    }

    private List<Path> rootsOf(Path repositoryRoot, String suffix) {
        List<String> modules = modulesOf(repositoryRoot);
        if (CollectionUtils.isEmpty(modules)) {
            Path root = repositoryRoot.resolve(suffix);
            return Files.isDirectory(root) ? List.of(root) : List.of();
        }

        List<Path> roots = new ArrayList<>();
        for (String module : modules) {
            Path root = repositoryRoot.resolve(module).resolve(suffix);
            if (Files.isDirectory(root)) {
                roots.add(root);
            }
        }
        return List.copyOf(roots);
    }

    private List<String> modulesOf(Path repositoryRoot) {
        Path pom = repositoryRoot.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return List.of();
        }

        try {
            String content = Files.readString(pom);
            if (!content.contains(AGGREGATOR_PACKAGING)) {
                return List.of();
            }
            List<String> modules = new ArrayList<>();
            Matcher matcher = MODULE_PATTERN.matcher(content);
            while (matcher.find()) {
                modules.add(matcher.group(1).trim());
            }
            return List.copyOf(modules);
        } catch (IOException e) {
            log.warn("Failed to read pom.xml for module detection: {}", pom, e);
            return List.of();
        }
    }
}
