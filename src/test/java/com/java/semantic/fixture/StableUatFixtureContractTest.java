package com.java.semantic.fixture;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class StableUatFixtureContractTest {

    private static final Path FIXTURES_ROOT = Path.of("fixtures", "uat");

    @Test
    void shouldKeepPaymentFixtureAsAStandaloneRuntimeDataBoundary() throws Exception {
        Path paymentFixture = FIXTURES_ROOT.resolve("payment-service");

        assertThat(paymentFixture).isDirectory();
        assertStandaloneFixtureBuild(paymentFixture);
        assertProductionSources(paymentFixture, List.of(
                "com/example/payment/FeeFormulaEvaluator.java",
                "com/example/payment/FeeFormulaUnavailableException.java",
                "com/example/payment/PaymentFeeCalculator.java",
                "com/example/payment/PaymentFeeSettings.java",
                "com/example/payment/PaymentMethod.java",
                "com/example/payment/PaymentQueryController.java"));
        assertFocusedTests(paymentFixture, List.of("com/example/payment/PaymentFeeCalculatorTest.java"));
        assertThat(productionDependencyArtifactIds(paymentFixture.resolve("pom.xml"))).containsExactly("spring-web");
        assertThat(testDependencyArtifactIds(paymentFixture.resolve("pom.xml")))
                .containsExactlyInAnyOrder("junit-jupiter", "assertj-core");

        String settings = readProductionSource(paymentFixture, "com/example/payment/PaymentFeeSettings.java");
        assertThat(settings).contains("Optional<String>", "loadFeeFormulaJson(PaymentMethod");

        String calculator = readProductionSource(paymentFixture, "com/example/payment/PaymentFeeCalculator.java");
        assertThat(calculator)
                .contains("settings.loadFeeFormulaJson(paymentMethod)", "orElseThrow", "FeeFormulaUnavailableException",
                        "feeFormulaEvaluator.evaluate(formulaJson, amount)")
                .doesNotContain("new BigDecimal", "BigDecimal.valueOf", "\"{", "%", "BNPL");

        String controller = readProductionSource(paymentFixture, "com/example/payment/PaymentQueryController.java");
        assertThat(controller).contains("@GetMapping", "PaymentMethod.values()");
        assertThat(allJavaSources(paymentFixture)).allSatisfy(source -> assertThat(source).doesNotContain("BNPL"));
        assertDoesNotDependOnSemanticOrRuntime(paymentFixture);
    }

    @Test
    void shouldKeepOrderFixtureAsAStandalonePersistedCancellationExample() throws Exception {
        Path orderFixture = FIXTURES_ROOT.resolve("order-service");

        assertThat(orderFixture).isDirectory();
        assertStandaloneFixtureBuild(orderFixture);
        assertProductionSources(orderFixture, List.of(
                "com/example/order/Order.java",
                "com/example/order/OrderCancellationException.java",
                "com/example/order/OrderNotFoundException.java",
                "com/example/order/OrderQueryController.java",
                "com/example/order/OrderRepository.java",
                "com/example/order/OrderService.java",
                "com/example/order/OrderStatus.java"));
        assertFocusedTests(orderFixture, List.of("com/example/order/OrderServiceTest.java"));
        assertThat(productionDependencyArtifactIds(orderFixture.resolve("pom.xml"))).containsExactly("spring-web");
        assertThat(testDependencyArtifactIds(orderFixture.resolve("pom.xml")))
                .containsExactlyInAnyOrder("junit-jupiter", "assertj-core");

        String controller = readProductionSource(orderFixture, "com/example/order/OrderQueryController.java");
        assertThat(controller).contains("@GetMapping", "@PostMapping", "orderService.findOrder", "orderService.cancel");

        String service = readProductionSource(orderFixture, "com/example/order/OrderService.java");
        assertThat(service).contains("orderRepository.findById(orderId)", "orderRepository.save(cancelledOrder)");

        String order = readProductionSource(orderFixture, "com/example/order/Order.java");
        String status = readProductionSource(orderFixture, "com/example/order/OrderStatus.java");
        assertThat(order).contains("OrderStatus.CANCELLED");
        assertThat(status).contains("CANCELLED");
        assertThat(allJavaSources(orderFixture)).allSatisfy(source ->
                assertThat(source.toLowerCase()).doesNotContain("refund"));
        assertDoesNotDependOnSemanticOrRuntime(orderFixture);
    }

    private static void assertStandaloneFixtureBuild(Path fixture) throws Exception {
        assertThat(Files.isSymbolicLink(fixture)).isFalse();
        assertThat(allPaths(fixture)).allSatisfy(path -> assertThat(Files.isSymbolicLink(path)).isFalse());

        String pom = Files.readString(fixture.resolve("pom.xml"));
        assertThat(pom).contains("<maven.compiler.release>21</maven.compiler.release>");
        assertThat(pom).doesNotContain("<parent>", "<relativePath>", "spring-boot", "spring-jdbc", "httpclient", "database");
        assertThat(allProductionSources(fixture)).allSatisfy(source ->
                assertThat(source).doesNotContain("SpringApplication", "@SpringBootApplication", "public static void main"));
    }

    private static void assertProductionSources(Path fixture, List<String> expectedSources) throws IOException {
        assertThat(relativeJavaSourcePaths(fixture.resolve("src/main/java"))).containsExactlyElementsOf(expectedSources);
    }

    private static void assertFocusedTests(Path fixture, List<String> expectedTests) throws IOException {
        assertThat(relativeJavaSourcePaths(fixture.resolve("src/test/java"))).containsExactlyElementsOf(expectedTests);
    }

    private static void assertDoesNotDependOnSemanticOrRuntime(Path fixture) throws IOException {
        assertThat(Files.readString(fixture.resolve("pom.xml")))
                .doesNotContain("com.java.semantic", "com.java.system.sessionagent", "java-semantic-service", "session-agent-runtime");
        assertThat(allJavaSources(fixture)).allSatisfy(source ->
                assertThat(source).doesNotContain(
                        "com.java.semantic", "com.java.system.sessionagent", "java-semantic-service", "session-agent-runtime"));
    }

    private static String readProductionSource(Path fixture, String relativePath) throws IOException {
        Path source = fixture.resolve("src/main/java").resolve(relativePath);
        assertThat(source).isRegularFile();
        return Files.readString(source);
    }

    private static List<String> allProductionSources(Path fixture) throws IOException {
        return javaSources(fixture.resolve("src/main/java"));
    }

    private static List<String> allJavaSources(Path fixture) throws IOException {
        return javaSources(fixture);
    }

    private static List<String> javaSources(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(StableUatFixtureContractTest::readUnchecked)
                    .toList();
        }
    }

    private static List<Path> allPaths(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.toList();
        }
    }

    private static List<String> relativeJavaSourcePaths(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .map(root::relativize)
                    .map(Path::toString)
                    .filter(path -> path.endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

    private static String readUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Fixture source cannot be read", exception);
        }
    }

    private static List<String> productionDependencyArtifactIds(Path pom) throws Exception {
        return dependencyArtifactIds(pom, "");
    }

    private static List<String> testDependencyArtifactIds(Path pom) throws Exception {
        return dependencyArtifactIds(pom, "test");
    }

    private static List<String> dependencyArtifactIds(Path pom, String expectedScope) throws Exception {
        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile());
        NodeList dependencies = document.getElementsByTagName("dependency");
        List<String> artifactIds = new ArrayList<>();
        for (int index = 0; index < dependencies.getLength(); index++) {
            Element dependency = (Element) dependencies.item(index);
            String scope = childText(dependency, "scope");
            if (scope.equals(expectedScope)) {
                artifactIds.add(childText(dependency, "artifactId"));
            }
        }
        return artifactIds;
    }

    private static String childText(Element parent, String tagName) {
        NodeList children = parent.getElementsByTagName(tagName);
        return IntStream.range(0, children.getLength())
                .mapToObj(children::item)
                .map(Node::getTextContent)
                .map(String::trim)
                .findFirst()
                .orElse("");
    }
}
