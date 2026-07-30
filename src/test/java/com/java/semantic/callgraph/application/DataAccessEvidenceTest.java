package com.java.semantic.callgraph.application;

import java.util.List;
import java.util.Optional;

import com.java.semantic.callgraph.domain.ResolutionStrategy;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.syntax.domain.ClassMetadata;
import com.java.semantic.syntax.domain.ClassMetadata.MethodSignature;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.SourceSlice;
import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.syntax.domain.SyntaxRange;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DataAccessEvidenceTest {

    private final DataAccessEvidence evidence = new DataAccessEvidence();

    @Test
    void should_match_mybatis_mapper_when_sql_source_present() {
        ClassMetadata orderMapper = metadata(
                "com.example.OrderMapper", ClassMetadata.TypeKind.INTERFACE, List.of(), List.of());
        MethodSignature selectOrder = method(
                "selectOrder", List.of("String"), "select * from orders where id = #{id}",
                ClassMetadata.SqlSource.ANNOTATION);
        MethodTarget declarationTarget = target("com.example.OrderMapper", "selectOrder");

        Optional<EvidenceMatch> match = evidence.evaluate(orderMapper, selectOrder, declarationTarget);

        assertThat(match).isPresent();
        assertThat(match.orElseThrow().strategy()).isEqualTo(ResolutionStrategy.MYBATIS_MAPPER);
        assertThat(match.orElseThrow().opaqueSymbol()).isEqualTo("com.example.OrderMapper#selectOrder(String)");
        assertThat(match.orElseThrow().evidence())
                .containsExactly("mapper SQL: select * from orders where id = #{id}");
        assertThat(match.orElseThrow().declarationTarget()).contains(declarationTarget);
    }

    @Test
    void should_prefer_mybatis_mapper_when_sql_source_and_spring_data_supertype_both_present() {
        ClassMetadata hybridMapper = metadata(
                "com.example.HybridMapper", ClassMetadata.TypeKind.INTERFACE,
                List.of(), List.of("JpaRepository"));
        MethodSignature selectOrder = method(
                "selectOrder", List.of("String"), "select * from orders where id = #{id}",
                ClassMetadata.SqlSource.ANNOTATION);
        MethodTarget declarationTarget = target("com.example.HybridMapper", "selectOrder");

        Optional<EvidenceMatch> match = evidence.evaluate(hybridMapper, selectOrder, declarationTarget);

        assertThat(match).isPresent();
        assertThat(match.orElseThrow().strategy()).isEqualTo(ResolutionStrategy.MYBATIS_MAPPER);
        assertThat(match.orElseThrow().opaqueSymbol()).isEqualTo("com.example.HybridMapper#selectOrder(String)");
        assertThat(match.orElseThrow().evidence())
                .containsExactly("mapper SQL: select * from orders where id = #{id}");
        assertThat(match.orElseThrow().declarationTarget()).contains(declarationTarget);
    }

    @Test
    void should_match_spring_data_repository_when_supertype_is_routed_into_implemented_types() {
        ClassMetadata orderRepository = metadata(
                "com.example.OrderRepository", ClassMetadata.TypeKind.INTERFACE,
                List.of(), List.of("JpaRepository"), List.of());
        MethodSignature findById = method("findById", List.of("Long"), null, null);
        MethodTarget declarationTarget = target("com.example.OrderRepository", "findById");

        Optional<EvidenceMatch> match = evidence.evaluate(orderRepository, findById, declarationTarget);

        assertThat(match).isPresent();
        assertThat(match.orElseThrow().strategy()).isEqualTo(ResolutionStrategy.SPRING_DATA_REPOSITORY);
        assertThat(match.orElseThrow().opaqueSymbol()).isEqualTo("com.example.OrderRepository#findById(Long)");
        assertThat(match.orElseThrow().evidence()).containsExactly("extends JpaRepository");
        assertThat(match.orElseThrow().declarationTarget()).contains(declarationTarget);
    }

    @Test
    void should_match_spring_data_repository_when_supertype_is_in_extended_types() {
        ClassMetadata orderRepository = metadata(
                "com.example.OrderRepository", ClassMetadata.TypeKind.INTERFACE,
                List.of(), List.of(), List.of("CrudRepository"));
        MethodSignature findById = method("findById", List.of("Long"), null, null);
        MethodTarget declarationTarget = target("com.example.OrderRepository", "findById");

        Optional<EvidenceMatch> match = evidence.evaluate(orderRepository, findById, declarationTarget);

        assertThat(match).isPresent();
        assertThat(match.orElseThrow().strategy()).isEqualTo(ResolutionStrategy.SPRING_DATA_REPOSITORY);
        assertThat(match.orElseThrow().evidence()).containsExactly("extends CrudRepository");
    }

    @Test
    void should_match_data_access_without_evidence_when_annotated_but_unproven() {
        ClassMetadata paymentMapper = metadata(
                "com.example.PaymentMapper", ClassMetadata.TypeKind.INTERFACE,
                List.of("Mapper"), List.of());
        MethodSignature insertPayment = method("insertPayment", List.of("Payment"), null, null);
        MethodTarget declarationTarget = target("com.example.PaymentMapper", "insertPayment");

        Optional<EvidenceMatch> match = evidence.evaluate(paymentMapper, insertPayment, declarationTarget);

        assertThat(match).isPresent();
        assertThat(match.orElseThrow().strategy()).isEqualTo(ResolutionStrategy.DATA_ACCESS_WITHOUT_EVIDENCE);
        assertThat(match.orElseThrow().opaqueSymbol()).isEqualTo("com.example.PaymentMapper#insertPayment(Payment)");
        assertThat(match.orElseThrow().evidence())
                .containsExactly("@Mapper without SQL or known supertype");
        assertThat(match.orElseThrow().declarationTarget()).contains(declarationTarget);
    }

    @Test
    void should_not_match_when_declaring_type_is_not_an_interface() {
        ClassMetadata orderMapperImpl = metadata(
                "com.example.OrderMapperImpl", ClassMetadata.TypeKind.CLASS,
                List.of("Repository"), List.of("JpaRepository"));
        MethodSignature selectOrder = method(
                "selectOrder", List.of("String"), "select * from orders where id = #{id}",
                ClassMetadata.SqlSource.ANNOTATION);
        MethodTarget declarationTarget = target("com.example.OrderMapperImpl", "selectOrder");

        assertThat(evidence.evaluate(orderMapperImpl, selectOrder, declarationTarget)).isEmpty();
    }

    private static ClassMetadata metadata(
            String fullyQualifiedName,
            ClassMetadata.TypeKind kind,
            List<String> annotations,
            List<String> extendedTypes) {
        return metadata(fullyQualifiedName, kind, annotations, List.of(), extendedTypes);
    }

    private static ClassMetadata metadata(
            String fullyQualifiedName,
            ClassMetadata.TypeKind kind,
            List<String> annotations,
            List<String> implementedTypes,
            List<String> extendedTypes) {
        int lastDot = fullyQualifiedName.lastIndexOf('.');
        String simpleName = fullyQualifiedName.substring(lastDot + 1);
        String packageName = lastDot >= 0 ? fullyQualifiedName.substring(0, lastDot) : "";
        SyntaxRange range = range(0, 0, 10, 0);
        return new ClassMetadata(
                simpleName, packageName, fullyQualifiedName,
                "src/main/java/" + fullyQualifiedName.replace('.', '/') + ".java",
                kind, false, implementedTypes, extendedTypes, annotations, List.of(),
                List.of(), List.of(), false, false, List.of(), range,
                new SourceSlice(range, "interface " + simpleName + " {}"), false, List.of());
    }

    private static MethodSignature method(
            String name, List<String> paramTypes, String sql, ClassMetadata.SqlSource sqlSource) {
        SyntaxRange range = range(0, 0, 1, 0);
        return new MethodSignature(
                name, paramTypes, List.of(), sql, sqlSource, 1, 2, range,
                new SourceSlice(range, name + "();"), List.of(), Optional.empty(), List.of(), List.of(),
                List.of(), range.start(), MethodTargetResolution.unresolved("test-fixture"), true, false, true);
    }

    private static MethodTarget target(String className, String methodName) {
        return new MethodTarget(className + ".java", "com.example", className, methodName, List.of());
    }

    private static SyntaxRange range(int startLine, int startCharacter, int endLine, int endCharacter) {
        return new SyntaxRange(
                new SyntaxPosition(startLine, startCharacter),
                new SyntaxPosition(endLine, endCharacter));
    }
}
