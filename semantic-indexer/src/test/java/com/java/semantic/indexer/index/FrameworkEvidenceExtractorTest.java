package com.java.semantic.indexer.index;

import com.java.semantic.model.index.RelationContract.Fact;
import com.java.semantic.model.index.RelationContract.FrameworkEvidence;
import com.java.semantic.model.index.RelationContract.Kind;
import com.java.semantic.model.index.RelationContract.ResolutionState;
import com.java.semantic.model.index.RelationContract.SourceSite;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class FrameworkEvidenceExtractorTest {
    @TempDir Path temporary;

    @Test
    void annotations_keep_occurrences_literals_and_import_provenance_separate() throws Exception {
        String source = """
                package sample;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.PutMapping;
                import org.springframework.web.bind.annotation.DeleteMapping;
                import org.springframework.web.bind.annotation.PatchMapping;
                import org.springframework.cloud.openfeign.FeignClient;
                import org.springframework.kafka.annotation.KafkaListener;
                import org.springframework.amqp.rabbit.annotation.RabbitListener;
                import org.springframework.data.jpa.repository.Query;
                import org.apache.ibatis.annotations.Select;
                import org.apache.ibatis.annotations.Insert;
                import org.apache.ibatis.annotations.Update;
                import org.apache.ibatis.annotations.Delete;
                @RequestMapping(path = "/api")
                class ApiController {
                    static final String MEDIA_TYPE = "application/json";
                    static final String ROUTE = "/orders";
                    static final String TOPIC_NAMES = "orders";
                    @GetMapping(path = "/café/商品/${id}", produces = MEDIA_TYPE)
                    void get() {}
                    @PostMapping(value = ROUTE)
                    void post() {}
                    @PutMapping("/put")
                    void put() {}
                    @DeleteMapping("/delete")
                    void delete() {}
                    @PatchMapping("/patch")
                    void patch() {}
                    @KafkaListener(topicPattern = "orders.*")
                    void consumeKafkaPattern() {}
                    @KafkaListener(topics = TOPIC_NAMES)
                    void consumeKafkaTopics() {}
                    @RabbitListener(queues = {"invoice.created", "invoice.paid"})
                    void consumeRabbit() {}
                }
                @FeignClient(name = "catalog", url = "${catalog.url}")
                interface CatalogClient {}
                @javax.persistence.Entity(name = "LegacyInvoice")
                @javax.persistence.Table(name = "legacy_invoice_records")
                class LegacyInvoice {}
                @jakarta.persistence.Entity(name = "InvoiceEntity")
                @jakarta.persistence.Table(name = "invoice_records")
                class Invoice {}
                @Query(value = "select id from invoice_records", nativeQuery = true)
                interface InvoiceRepository {}
                interface InvoiceMapper {
                    String INSERT_SQL = "insert into invoice_records (id) values (?)";
                    @Select("select * from invoice_records where id = #{id}")
                    Invoice findById(long id);
                    @Insert(INSERT_SQL)
                    int insert(Invoice invoice);
                    @Update("update invoice_records set state = 'ready' where id = #{id}")
                    int update(Invoice invoice);
                    @Delete("delete from invoice_records where id = #{id}")
                    int delete(long id);
                }
                """.replace("\n", "\r\n");
        String getMappingSource = "@GetMapping(path = \"/café/商品/${id}\", produces = MEDIA_TYPE)";

        try (RelationExtractionFixture fixture = new RelationExtractionFixture(temporary, Map.of("Api.java", source))) {
            List<Fact> facts = extract(fixture);
            assertThat(facts).hasSize(19);
            assertThat(facts).extracting(fact -> fact.framework().orElseThrow().recognizerId())
                    .containsExactlyInAnyOrder(
                            "spring.request-mapping", "spring.feign-client", "spring.get-mapping",
                            "spring.post-mapping", "spring.put-mapping", "spring.delete-mapping", "spring.patch-mapping",
                            "spring.kafka-listener", "spring.kafka-listener", "spring.rabbit-listener",
                            "jpa.javax-entity", "jpa.javax-table", "jpa.jakarta-entity", "jpa.jakarta-table",
                            "spring-data-jpa-query", "mybatis-select", "mybatis-insert", "mybatis-update", "mybatis-delete");

            Fact classMapping = fact(fixture, facts, Kind.HTTP_EVIDENCE, "@RequestMapping(path = \"/api\")");
            Fact methodMapping = fact(fixture, facts, Kind.HTTP_EVIDENCE, getMappingSource);
            assertCandidateWithoutTarget(classMapping, "DIRECT_IMPORT");
            assertCandidateWithoutTarget(methodMapping, "DIRECT_IMPORT");
            assertThat(classMapping.framework().orElseThrow().literalValues()).containsEntry("path", List.of("/api"));
            FrameworkEvidence mappingEvidence = methodMapping.framework().orElseThrow();
            assertThat(mappingEvidence.literalValues()).containsEntry("path", List.of("/café/商品/${id}"));
            assertThat(mappingEvidence.unresolvedMembers()).contains("produces");
            assertThat(mappingEvidence.bindingUncertainties()).contains("path: written placeholder has unresolved runtime binding");
            assertThat(fixture.slice(methodMapping)).isEqualTo(getMappingSource);
            int annotationOffset = source.indexOf(getMappingSource);
            int expectedStartByte = source.substring(0, annotationOffset).getBytes(StandardCharsets.UTF_8).length;
            long expectedByteLength = getMappingSource.getBytes(StandardCharsets.UTF_8).length;
            assertThat(methodMapping.source().range().startByte()).isEqualTo(expectedStartByte);
            assertThat(methodMapping.source().range().endByte()).isEqualTo(expectedStartByte + expectedByteLength);
            assertThat(methodMapping.source().range().startLine())
                    .isEqualTo(source.substring(0, annotationOffset).split("\\r\\n", -1).length);
            List<String> mappingSupport = mappingEvidence.supportingEvidence().stream()
                    .map(site -> rawSlice(fixture, site)).toList();
            assertThat(mappingSupport).contains("GetMapping", "org.springframework.web.bind.annotation.GetMapping");

            Fact feign = fact(fixture, facts, Kind.HTTP_EVIDENCE,
                    "@FeignClient(name = \"catalog\", url = \"${catalog.url}\")");
            assertThat(feign.framework().orElseThrow().literalValues())
                    .containsEntry("name", List.of("catalog"))
                    .containsEntry("url", List.of("${catalog.url}"));
            assertThat(feign.framework().orElseThrow().bindingUncertainties())
                    .contains("url: written placeholder has unresolved runtime binding");
            Fact postMapping = fact(fixture, facts, Kind.HTTP_EVIDENCE, "@PostMapping(value = ROUTE)");
            assertThat(postMapping.framework().orElseThrow().literalValues()).doesNotContainKey("value");
            assertThat(postMapping.framework().orElseThrow().unresolvedMembers()).contains("value");

            Fact kafkaPattern = fact(fixture, facts, Kind.EVENT_EVIDENCE,
                    "@KafkaListener(topicPattern = \"orders.*\")");
            assertThat(kafkaPattern.framework().orElseThrow().literalValues())
                    .containsEntry("topicPattern", List.of("orders.*"))
                    .doesNotContainKey("topics");
            Fact kafkaTopics = fact(fixture, facts, Kind.EVENT_EVIDENCE,
                    "@KafkaListener(topics = TOPIC_NAMES)");
            assertThat(kafkaTopics.framework().orElseThrow().literalValues())
                    .doesNotContainKey("topicPattern")
                    .doesNotContainKey("topics");
            assertThat(kafkaTopics.framework().orElseThrow().unresolvedMembers()).containsExactly("topics");
            Fact rabbitListener = fact(fixture, facts, Kind.EVENT_EVIDENCE,
                    "@RabbitListener(queues = {\"invoice.created\", \"invoice.paid\"})");
            assertThat(rabbitListener.framework().orElseThrow().literalValues())
                    .containsEntry("queues", List.of("invoice.created", "invoice.paid"));

            Fact legacyEntity = fact(fixture, facts, Kind.DB_EVIDENCE,
                    "@javax.persistence.Entity(name = \"LegacyInvoice\")");
            Fact legacyTable = fact(fixture, facts, Kind.DB_EVIDENCE,
                    "@javax.persistence.Table(name = \"legacy_invoice_records\")");
            Fact entity = fact(fixture, facts, Kind.DB_EVIDENCE,
                    "@jakarta.persistence.Entity(name = \"InvoiceEntity\")");
            Fact table = fact(fixture, facts, Kind.DB_EVIDENCE,
                    "@jakarta.persistence.Table(name = \"invoice_records\")");
            assertCandidateWithoutTarget(legacyEntity, "FULLY_QUALIFIED");
            assertCandidateWithoutTarget(legacyTable, "FULLY_QUALIFIED");
            assertCandidateWithoutTarget(entity, "FULLY_QUALIFIED");
            assertCandidateWithoutTarget(table, "FULLY_QUALIFIED");
            assertThat(legacyEntity.framework().orElseThrow().literalValues()).containsEntry("name", List.of("LegacyInvoice"));
            assertThat(legacyTable.framework().orElseThrow().literalValues())
                    .containsEntry("name", List.of("legacy_invoice_records"));
            assertThat(entity.framework().orElseThrow().literalValues()).containsEntry("name", List.of("InvoiceEntity"));
            assertThat(table.framework().orElseThrow().literalValues()).containsEntry("name", List.of("invoice_records"));

            Fact select = fact(fixture, facts, Kind.DB_EVIDENCE,
                    "@Select(\"select * from invoice_records where id = #{id}\")");
            assertThat(select.framework().orElseThrow().literalValues())
                    .containsEntry("value", List.of("select * from invoice_records where id = #{id}"));
            assertThat(select.framework().orElseThrow().bindingUncertainties())
                    .contains("value: written placeholder has unresolved runtime binding");
            Fact insert = fact(fixture, facts, Kind.DB_EVIDENCE, "@Insert(INSERT_SQL)");
            assertThat(insert.framework().orElseThrow().literalValues()).doesNotContainKey("value");
            assertThat(insert.framework().orElseThrow().unresolvedMembers()).contains("value");

            for (Fact evidenceFact : facts) {
                FrameworkEvidence evidence = evidenceFact.framework().orElseThrow();
                assertThat(evidenceFact.resolutionState()).isEqualTo(ResolutionState.CANDIDATE);
                for (String unresolvedMember : evidence.unresolvedMembers()) {
                    assertThat(evidence.literalValues()).doesNotContainKey(unresolvedMember);
                }
            }
        }
    }

    @Test
    void jdk_jdbc_signatures_keep_literal_and_dynamic_sql_at_call_spans() throws Exception {
        String source = """
                package sample;
                import java.sql.Connection;
                import java.sql.Statement;
                class Queries {
                    void read(Connection connection, Statement statement, String dynamicSql) throws Exception {
                        connection.prepareStatement("select tier from vip where user_id = ?");
                        connection.prepareStatement("select id from account", Statement.RETURN_GENERATED_KEYS);
                        connection.prepareStatement(dynamicSql);
                        statement.executeQuery("select balance from account");
                        statement.executeQuery(dynamicSql);
                    }
                }
                """;

        try (RelationExtractionFixture fixture = new RelationExtractionFixture(temporary, Map.of("Queries.java", source))) {
            List<Fact> facts = extract(fixture);
            assertThat(facts).hasSize(5);

            Fact literal = fact(fixture, facts, Kind.DB_EVIDENCE,
                    "connection.prepareStatement(\"select tier from vip where user_id = ?\")");
            assertJdbcStaticApi(literal, "java.sql.Connection", "java.sql.Connection.prepareStatement(java.lang.String)");
            assertThat(literal.framework().orElseThrow().literalValues())
                    .containsEntry("sql", List.of("select tier from vip where user_id = ?"))
                    .containsOnlyKeys("sql");
            assertThat(literal.framework().orElseThrow().unresolvedMembers()).isEmpty();

            Fact overloaded = fact(fixture, facts, Kind.DB_EVIDENCE,
                    "connection.prepareStatement(\"select id from account\", Statement.RETURN_GENERATED_KEYS)");
            assertJdbcStaticApi(overloaded, "java.sql.Connection",
                    "java.sql.Connection.prepareStatement(java.lang.String, int)");
            assertThat(overloaded.framework().orElseThrow().literalValues())
                    .containsEntry("sql", List.of("select id from account"))
                    .containsOnlyKeys("sql");
            assertThat(overloaded.framework().orElseThrow().unresolvedMembers()).isEmpty();

            Fact dynamic = fact(fixture, facts, Kind.DB_EVIDENCE, "connection.prepareStatement(dynamicSql)");
            assertJdbcStaticApi(dynamic, "java.sql.Connection", "java.sql.Connection.prepareStatement(java.lang.String)");
            assertThat(dynamic.framework().orElseThrow().literalValues()).doesNotContainKey("sql");
            assertThat(dynamic.framework().orElseThrow().unresolvedMembers()).containsExactly("sql");

            Fact statementLiteral = fact(fixture, facts, Kind.DB_EVIDENCE,
                    "statement.executeQuery(\"select balance from account\")");
            assertJdbcStaticApi(statementLiteral, "java.sql.Statement", "java.sql.Statement.executeQuery(java.lang.String)");
            assertThat(statementLiteral.framework().orElseThrow().literalValues())
                    .containsEntry("sql", List.of("select balance from account"))
                    .containsOnlyKeys("sql");
            assertThat(statementLiteral.framework().orElseThrow().unresolvedMembers()).isEmpty();
            Fact statementDynamic = fact(fixture, facts, Kind.DB_EVIDENCE, "statement.executeQuery(dynamicSql)");
            assertJdbcStaticApi(statementDynamic, "java.sql.Statement", "java.sql.Statement.executeQuery(java.lang.String)");
            assertThat(statementDynamic.framework().orElseThrow().literalValues()).doesNotContainKey("sql");
            assertThat(statementDynamic.framework().orElseThrow().unresolvedMembers()).containsExactly("sql");
            assertThat(facts).allSatisfy(fact -> assertThat(fixture.slice(fact)).contains("("));
        }
    }

    @Test
    void kafka_and_rabbit_calls_without_approved_artifacts_keep_overload_roles_unresolved() throws Exception {
        String source = """
                package sample;
                import org.springframework.kafka.core.KafkaTemplate;
                import org.springframework.amqp.rabbit.core.RabbitTemplate;
                class Producers {
                    void send(KafkaTemplate<String, Object> kafka, RabbitTemplate rabbit,
                            String dynamicTopic, Object message) {
                        kafka.send("orders", message);
                        kafka.send(dynamicTopic, message);
                        kafka.send("orders", 0, "order-7", message);
                        rabbit.convertAndSend("orders.created", message);
                        rabbit.convertAndSend("orders.exchange", "orders.created", message);
                    }
                }
                """;

        try (RelationExtractionFixture fixture = new RelationExtractionFixture(temporary, Map.of("Producers.java", source))) {
            List<Fact> facts = extract(fixture);
            assertThat(facts).hasSize(5);

            Fact kafkaSimple = fact(fixture, facts, Kind.EVENT_EVIDENCE, "kafka.send(\"orders\", message)");
            assertSyntacticOverloadUncertainty(kafkaSimple, "DIRECT_IMPORT", List.of("topic"));
            assertThat(kafkaSimple.framework().orElseThrow().recognizerId()).isEqualTo("spring-kafka-send");
            List<String> kafkaSupport = kafkaSimple.framework().orElseThrow().supportingEvidence().stream()
                    .map(site -> rawSlice(fixture, site)).toList();
            assertThat(kafkaSupport).contains("org.springframework.kafka.core.KafkaTemplate");
            Fact kafkaDynamic = fact(fixture, facts, Kind.EVENT_EVIDENCE, "kafka.send(dynamicTopic, message)");
            assertSyntacticOverloadUncertainty(kafkaDynamic, "DIRECT_IMPORT", List.of("topic"));
            Fact kafkaPartitioned = fact(fixture, facts, Kind.EVENT_EVIDENCE,
                    "kafka.send(\"orders\", 0, \"order-7\", message)");
            assertSyntacticOverloadUncertainty(kafkaPartitioned, "DIRECT_IMPORT", List.of("topic"));

            Fact rabbitDefault = fact(fixture, facts, Kind.EVENT_EVIDENCE,
                    "rabbit.convertAndSend(\"orders.created\", message)");
            assertSyntacticOverloadUncertainty(rabbitDefault, "DIRECT_IMPORT", List.of("exchange", "routingKey"));
            Fact rabbitExplicit = fact(fixture, facts, Kind.EVENT_EVIDENCE,
                    "rabbit.convertAndSend(\"orders.exchange\", \"orders.created\", message)");
            assertSyntacticOverloadUncertainty(rabbitExplicit, "DIRECT_IMPORT", List.of("exchange", "routingKey"));
            assertThat(rabbitExplicit.framework().orElseThrow().recognizerId())
                    .isEqualTo("spring-rabbit-convert-and-send");
        }
    }

    @Test
    void inherited_receiver_type_uses_declaring_unit_imports_and_raw_support() throws Exception {
        String base = """
                package sample;
                import org.springframework.kafka.core.KafkaTemplate;
                class Base {
                    protected KafkaTemplate<String, Object> kafka;
                }
                """;
        String child = """
                package sample;
                class Child extends Base {
                    void publish(Object payload) {
                        kafka.send("orders", payload);
                    }
                }
                """;
        Map<String, String> sources = Map.of("src/Base.java", base, "src/Child.java", child);

        try (RelationExtractionFixture fixture = new RelationExtractionFixture(temporary, sources)) {
            List<Fact> facts = extract(fixture);
            assertThat(facts).hasSize(1);
            Fact evidence = fact(fixture, facts, Kind.EVENT_EVIDENCE, "kafka.send(\"orders\", payload)");
            assertSyntacticOverloadUncertainty(evidence, "DIRECT_IMPORT", List.of("topic"));
            assertThat(evidence.source().path()).isEqualTo("src/Child.java");
            assertThat(evidence.framework().orElseThrow().supportingEvidence())
                    .extracting(SourceSite::path).containsOnly("src/Base.java");
            List<String> supportingText = evidence.framework().orElseThrow().supportingEvidence().stream()
                    .map(site -> rawSlice(fixture, site)).toList();
            assertThat(supportingText).contains("org.springframework.kafka.core.KafkaTemplate");
            assertThat(supportingText).anyMatch(value -> value.startsWith("KafkaTemplate"));
        }
    }

    @Test
    void resolved_user_annotation_with_framework_simple_name_is_not_recognized() throws Exception {
        Map<String, String> sources = Map.of(
                "src/sample/GetMapping.java", """
                        package sample;
                        public @interface GetMapping {
                            String value() default "";
                        }
                        """,
                "src/sample/Controller.java", """
                        package sample;
                        import org.springframework.web.bind.annotation.*;
                        @GetMapping("/user")
                        class Controller {}
                        """);

        try (RelationExtractionFixture fixture = new RelationExtractionFixture(temporary, sources)) {
            assertThat(extract(fixture)).isEmpty();
        }
    }

    @Test
    void ordinary_user_send_query_and_save_methods_are_not_framework_evidence() throws Exception {
        String source = """
                package sample;
                class PlainSender { void send(String topic, Object payload) {} }
                class PlainDatabase { void query(String sql) {} }
                class PlainRepository { void save(Object entity) {} }
                class Service {
                    void run(PlainSender sender, PlainDatabase database, PlainRepository repository) {
                        sender.send("orders", "payload");
                        database.query("select * from account");
                        repository.save("ordinary value");
                    }
                }
                """;

        try (RelationExtractionFixture fixture = new RelationExtractionFixture(temporary, Map.of("Service.java", source))) {
            assertThat(extract(fixture)).isEmpty();
        }
    }

    private static List<Fact> extract(RelationExtractionFixture fixture) {
        List<Fact> facts = new ArrayList<>();
        FrameworkEvidenceExtractor extractor = new FrameworkEvidenceExtractor();
        for (ParsedJavaUnit unit : fixture.units.values()) {
            extractor.extract(unit, fixture.session, facts::add);
        }
        return List.copyOf(facts);
    }

    private static Fact fact(RelationExtractionFixture fixture, List<Fact> facts, Kind kind, String source) {
        return facts.stream().filter(value -> value.kind() == kind && fixture.slice(value).equals(source))
                .findFirst().orElseThrow();
    }

    private static void assertCandidateWithoutTarget(Fact fact, String basis) {
        assertThat(fact.resolutionState()).isEqualTo(ResolutionState.CANDIDATE);
        assertThat(fact.target()).isEmpty();
        assertThat(fact.unresolvedReason()).isEmpty();
        assertThat(fact.framework().orElseThrow().recognitionBasis()).isEqualTo(basis);
    }

    private static void assertSyntacticOverloadUncertainty(Fact fact, String basis, List<String> unresolvedRoles) {
        assertCandidateWithoutTarget(fact, basis);
        FrameworkEvidence evidence = fact.framework().orElseThrow();
        assertThat(evidence.literalValues()).isEmpty();
        assertThat(evidence.unresolvedMembers()).containsExactlyElementsOf(unresolvedRoles);
        assertThat(evidence.bindingUncertainties())
                .contains("overload signature has no verified argument-role mapping");
    }

    private static void assertJdbcStaticApi(Fact fact, String owner, String signature) {
        assertThat(fact.resolutionState()).isEqualTo(ResolutionState.CANDIDATE);
        assertThat(fact.framework().orElseThrow().recognitionBasis()).isEqualTo("STATIC_API");
        assertThat(fact.target().orElseThrow().external().orElseThrow().qualifiedOwner()).isEqualTo(owner);
        assertThat(fact.target().orElseThrow().external().orElseThrow().signature()).isEqualTo(signature);
        assertThat(fact.target().orElseThrow().external().orElseThrow().module()).contains("java.sql");
    }

    private static String rawSlice(RelationExtractionFixture fixture, SourceSite site) {
        byte[] source = fixture.bytes.get(site.path());
        int start = Math.toIntExact(site.range().startByte());
        int length = Math.toIntExact(site.range().endByte() - site.range().startByte());
        return new String(source, start, length, StandardCharsets.UTF_8);
    }
}
