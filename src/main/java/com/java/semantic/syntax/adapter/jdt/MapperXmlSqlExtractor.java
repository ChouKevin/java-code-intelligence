package com.java.semantic.syntax.adapter.jdt;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import com.java.semantic.syntax.domain.MapperEvidenceIndex;
import com.java.semantic.syntax.domain.MapperEvidenceRepresentation;
import com.java.semantic.syntax.domain.MapperFragmentEvidence;
import com.java.semantic.syntax.domain.MapperFragmentIdentity;
import com.java.semantic.syntax.domain.MapperStatementEvidence;
import com.java.semantic.syntax.domain.MapperStatementIdentity;
import com.java.semantic.syntax.domain.MapperStatementKey;
import com.java.semantic.repository.domain.RepositorySourceContainment;
import com.java.semantic.repository.domain.RepositorySourceContainmentResult;

import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
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

    private static final String FRAGMENT_ELEMENT = "sql";

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

    /** 同一次安全 XML 解析產生的既有 SQL lookup 與原始 mapper 證據 */
    record Extraction(SqlIndex sqlIndex, MapperEvidenceIndex evidenceIndex) {
    }

    SqlIndex index(Path repositoryRoot, List<Path> resourceRoots) {
        return extract(repositoryRoot, resourceRoots).sqlIndex();
    }

    Extraction extract(Path repositoryRoot, List<Path> resourceRoots) {
        if (CollectionUtils.isEmpty(resourceRoots)) {
            return new Extraction(SqlIndex.empty(), MapperEvidenceIndex.empty());
        }

        RepositorySourceContainment containment = new RepositorySourceContainment();
        Map<String, Map<String, String>> byNamespace = new HashMap<>();
        List<MapperStatementEvidence> statements = new ArrayList<>();
        List<MapperFragmentEvidence> fragments = new ArrayList<>();
        for (Path resourceRoot : resourceRoots) {
            indexRoot(containment, repositoryRoot, resourceRoot, byNamespace, statements, fragments);
        }
        return new Extraction(sqlIndexOf(byNamespace), new MapperEvidenceIndex(statements, fragments));
    }

    private void indexRoot(
            RepositorySourceContainment containment,
            Path repositoryRoot,
            Path resourceRoot,
            Map<String, Map<String, String>> byNamespace,
            List<MapperStatementEvidence> statementEvidence,
            List<MapperFragmentEvidence> fragments) {
        try (Stream<Path> walk = Files.walk(resourceRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".xml"))
                    .filter(path -> containment.classify(repositoryRoot, path)
                            instanceof RepositorySourceContainmentResult.ContainedSource)
                    .sorted()
                    .forEach(path -> indexFile(repositoryRoot, path, byNamespace, statementEvidence, fragments));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to walk resource root: " + resourceRoot, e);
        }
    }

    private void indexFile(
            Path repositoryRoot,
            Path xmlPath,
            Map<String, Map<String, String>> byNamespace,
            List<MapperStatementEvidence> statementEvidence,
            List<MapperFragmentEvidence> fragments) {
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

            Map<String, String> normalizedStatements = byNamespace.computeIfAbsent(namespace, key -> new HashMap<>());
            for (String tag : SQL_TAGS) {
                indexTag(root, tag, normalizedStatements);
            }
            indexEvidence(root, namespace, repositoryRelativePath(repositoryRoot, xmlPath), statementEvidence, fragments);
        } catch (Exception exception) {
            log.warn("Mapper XML skipped category={} exceptionType={}",
                    "MAPPER_XML_PARSE_FAILED", exception.getClass().getSimpleName());
        }
    }

    private void indexEvidence(
            Element root,
            String namespace,
            String resourcePath,
            List<MapperStatementEvidence> statements,
            List<MapperFragmentEvidence> fragments) throws TransformerException {
        int documentOrdinal = 0;
        for (Node child = root.getFirstChild(); Objects.nonNull(child); child = child.getNextSibling()) {
            if (!(child instanceof Element element)) {
                continue;
            }
            if (SQL_TAGS.contains(element.getNodeName())) {
                statementEvidenceOf(namespace, resourcePath, documentOrdinal, element).ifPresent(statements::add);
            } else if (FRAGMENT_ELEMENT.equals(element.getNodeName())) {
                fragmentEvidenceOf(namespace, resourcePath, documentOrdinal, element).ifPresent(fragments::add);
            }
            documentOrdinal++;
        }
    }

    private Optional<MapperStatementEvidence> statementEvidenceOf(
            String namespace,
            String resourcePath,
            int documentOrdinal,
            Element element) throws TransformerException {
        String statementId = element.getAttribute("id");
        if (!StringUtils.hasText(statementId)) {
            return Optional.empty();
        }
        MapperStatementIdentity identity = new MapperStatementIdentity(
                new MapperStatementKey(namespace, statementId),
                resourcePath,
                optionalAttribute(element, "databaseId"),
                documentOrdinal,
                MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);
        return Optional.of(new MapperStatementEvidence(
                identity,
                element.getNodeName(),
                serialize(element),
                includeRefIdsOf(element),
                Optional.empty()));
    }

    private Optional<MapperFragmentEvidence> fragmentEvidenceOf(
            String namespace,
            String resourcePath,
            int documentOrdinal,
            Element element) throws TransformerException {
        String fragmentId = element.getAttribute("id");
        if (!StringUtils.hasText(fragmentId)) {
            return Optional.empty();
        }
        MapperFragmentIdentity identity = new MapperFragmentIdentity(
                namespace,
                fragmentId,
                resourcePath,
                documentOrdinal,
                MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);
        return Optional.of(new MapperFragmentEvidence(identity, serialize(element)));
    }

    private static Optional<String> optionalAttribute(Element element, String name) {
        String value = element.getAttribute(name);
        return StringUtils.hasText(value) ? Optional.of(value) : Optional.empty();
    }

    private static List<String> includeRefIdsOf(Element element) {
        List<String> refIds = new ArrayList<>();
        NodeList includes = element.getElementsByTagName("include");
        for (int index = 0; index < includes.getLength(); index++) {
            Element include = (Element) includes.item(index);
            optionalAttribute(include, "refid").ifPresent(refIds::add);
        }
        return List.copyOf(refIds);
    }

    private static String serialize(Element element) throws TransformerException {
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Transformer transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        transformer.setOutputProperty(OutputKeys.INDENT, "no");
        StringWriter output = new StringWriter();
        transformer.transform(new DOMSource(element), new StreamResult(output));
        return output.toString();
    }

    private static String repositoryRelativePath(Path repositoryRoot, Path xmlPath) {
        return repositoryRoot.relativize(xmlPath).toString().replace('\\', '/');
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

    private static SqlIndex sqlIndexOf(Map<String, Map<String, String>> byNamespace) {
        Map<String, Map<String, String>> immutableNamespaces = new HashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : byNamespace.entrySet()) {
            immutableNamespaces.put(entry.getKey(), Map.copyOf(entry.getValue()));
        }
        return new SqlIndex(Map.copyOf(immutableNamespaces));
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
