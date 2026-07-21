package com.java.semantic.syntax.adapter.jdt;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import lombok.extern.slf4j.Slf4j;

/** 從 MyBatis mapper XML 抽取 SQL，以 namespace 與 statement id 索引 */
@Slf4j
class MapperXmlSqlExtractor {

    /**
     * 掃描順序固定
     * <p>
     * 舊分析器用 Set.of，同一個 id 出現在兩個標籤下時每次 JVM 執行的勝出者都可能不同
     */
    private static final List<String> SQL_TAGS = List.of("select", "insert", "update", "delete");

    private static final String MAPPER_ELEMENT = "mapper";

    private final DocumentBuilderFactory xmlFactory = newXmlFactory();

    /** namespace 與 statement id 對應到 SQL 的索引 */
    record SqlIndex(Map<String, Map<String, String>> byNamespace) {

        Optional<String> find(String namespace, String statementId) {
            return Optional.ofNullable(byNamespace.get(namespace))
                    .map(statements -> statements.get(statementId));
        }

        static SqlIndex empty() {
            return new SqlIndex(Map.of());
        }
    }

    SqlIndex index(Path repositoryRoot, List<Path> resourceRoots) {
        if (CollectionUtils.isEmpty(resourceRoots)) {
            return SqlIndex.empty();
        }

        RepositoryContainment containment = RepositoryContainment.of(repositoryRoot);
        Map<String, Map<String, String>> byNamespace = new HashMap<>();
        for (Path resourceRoot : resourceRoots) {
            indexRoot(containment, resourceRoot, byNamespace);
        }
        return new SqlIndex(Map.copyOf(byNamespace));
    }

    private void indexRoot(RepositoryContainment containment, Path resourceRoot,
            Map<String, Map<String, String>> byNamespace) {
        try (Stream<Path> walk = Files.walk(resourceRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".xml"))
                    .filter(containment::contains)
                    .sorted()
                    .forEach(path -> indexFile(path, byNamespace));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to walk resource root: " + resourceRoot, e);
        }
    }

    private void indexFile(Path xmlPath, Map<String, Map<String, String>> byNamespace) {
        try {
            DocumentBuilder builder = xmlFactory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            builder.setErrorHandler(new ThrowingErrorHandler());

            Document document = builder.parse(xmlPath.toFile());
            Element root = document.getDocumentElement();
            if (!MAPPER_ELEMENT.equals(root.getNodeName())) {
                return;
            }

            String namespace = root.getAttribute("namespace");
            if (!StringUtils.hasText(namespace)) {
                return;
            }

            Map<String, String> statements = byNamespace.computeIfAbsent(namespace, key -> new HashMap<>());
            for (String tag : SQL_TAGS) {
                indexTag(root, tag, statements);
            }
        } catch (Exception exception) {
            log.warn("Mapper XML skipped category={} exceptionType={}",
                    "MAPPER_XML_PARSE_FAILED", exception.getClass().getSimpleName());
        }
    }

    private void indexTag(Element root, String tag, Map<String, String> statements) {
        NodeList nodes = root.getElementsByTagName(tag);
        for (int index = 0; index < nodes.getLength(); index++) {
            Element element = (Element) nodes.item(index);
            String id = element.getAttribute("id");
            String sql = normalize(element.getTextContent());
            if (StringUtils.hasText(id) && StringUtils.hasText(sql)) {
                statements.putIfAbsent(id, sql);
            }
        }
    }

    private String normalize(String raw) {
        return raw.replaceAll("\\s+", " ").trim();
    }

    private static final class ThrowingErrorHandler extends DefaultHandler {

        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }
    }

    private static DocumentBuilderFactory newXmlFactory() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/validation", false);
            factory.setExpandEntityReferences(false);
            return factory;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("Failed to configure the mapper XML parser factory", e);
        }
    }
}
