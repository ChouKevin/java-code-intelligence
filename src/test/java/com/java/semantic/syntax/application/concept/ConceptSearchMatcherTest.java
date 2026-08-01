package com.java.semantic.syntax.application.concept;

import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.syntax.domain.SourceMemberIdentity.TypeMember;
import com.java.semantic.syntax.application.concept.DeclarationConceptIdentity.FieldConceptIdentity;
import com.java.semantic.syntax.application.concept.DeclarationConceptIdentity.MethodConceptIdentity;
import com.java.semantic.syntax.application.concept.EntryPointConceptIdentity.MqDestinationConceptIdentity;
import com.java.semantic.syntax.application.concept.EntryPointConceptIdentity.ScheduleConceptIdentity;
import com.java.semantic.syntax.application.concept.MapperConceptIdentity.MapperStatementConceptIdentity;
import com.java.semantic.syntax.domain.MapperStatementKey;
import com.java.semantic.syntax.domain.MqBroker;
import com.java.semantic.syntax.domain.NamedTypeReference;
import com.java.semantic.syntax.domain.ScheduleTriggerKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** 結構化概念搜尋比對規則 */
class ConceptSearchMatcherTest {

    private final ConceptSearchMatcher matcher = new ConceptSearchMatcher();

    private final ConceptSearchDocumentProjector projector = new ConceptSearchDocumentProjector();

    @Test
    void should_tokenize_camel_pascal_snake_kebab_dot_and_route_boundaries() {
        Set<String> tokens = ConceptSearchTokenizer.tokenize(
                "OrderService findPendingOrders purchase_order order-items orders.created /api/order-items");

        assertThat(tokens).containsExactlyInAnyOrder(
                "order", "service", "find", "pending", "orders", "purchase", "items", "created", "api");
    }

    @Test
    void should_match_exact_and_prefix_values_case_insensitively() {
        ConceptCatalogEntry entry = entry("com.acme.order.OrderService#createOrder(java.lang.String)");

        assertThat(matches(entry, new ConceptSearchTerm("order", ConceptMatchMode.TOKEN_EXACT)))
                .isTrue();
        assertThat(matches(entry, new ConceptSearchTerm("cre", ConceptMatchMode.TOKEN_PREFIX)))
                .isTrue();
    }

    @Test
    void should_require_every_term_to_match_the_same_entry_without_stemming() {
        ConceptCatalogEntry entry = entry("com.acme.order.OrderService#createOrder(java.lang.String)");

        assertThat(matcher.matches(projector.project(entry), List.of(
                new ConceptSearchTerm("order", ConceptMatchMode.TOKEN_EXACT),
                new ConceptSearchTerm("create", ConceptMatchMode.TOKEN_PREFIX)))).isTrue();
        assertThat(matches(entry, new ConceptSearchTerm("orders", ConceptMatchMode.TOKEN_EXACT)))
                .isFalse();
    }

    @Test
    void should_accept_only_trailing_prefix_shorthand_and_reject_control_characters() {
        ConceptSearchTerm shorthand = new ConceptSearchTerm("order*", ConceptMatchMode.TOKEN_EXACT);

        assertThat(shorthand.value()).isEqualTo("order");
        assertThat(shorthand.matchMode()).isEqualTo(ConceptMatchMode.TOKEN_PREFIX);
        assertThatIllegalArgumentException().isThrownBy(() -> new ConceptSearchTerm("*", ConceptMatchMode.TOKEN_PREFIX));
        assertThatIllegalArgumentException().isThrownBy(() -> new ConceptSearchTerm("or*der", ConceptMatchMode.TOKEN_PREFIX));
        assertThatIllegalArgumentException().isThrownBy(() -> new ConceptSearchTerm("order**", ConceptMatchMode.TOKEN_PREFIX));
        assertThatIllegalArgumentException().isThrownBy(() -> new ConceptSearchTerm("order\n", ConceptMatchMode.TOKEN_PREFIX));
        assertThatIllegalArgumentException().isThrownBy(() -> new ConceptSearchTerm("order\u0000", ConceptMatchMode.TOKEN_PREFIX));
    }

    @Test
    void should_exclude_all_and_unresolved_from_search_tokens() {
        Set<String> tokens = ConceptSearchTokenizer.tokenize("ALL <unresolved> OrderService");

        assertThat(tokens).contains("order", "service");
        assertThat(tokens).doesNotContain("all", "unresolved");
    }

    @Test
    void should_preserve_identifier_tokens_that_match_metadata_sentinel_words() {
        Set<String> tokens = ConceptSearchTokenizer.tokenize("ALL <unresolved> AllOrdersController UnresolvedOrder");

        assertThat(tokens).contains("all", "orders", "controller", "unresolved", "order");
        assertThatCode(() -> entry("com.acme.order.AllOrdersController#unresolvedOrder()"))
                .doesNotThrowAnyException();
    }

    @Test
    void should_preserve_field_declared_type_search_tokens_from_field_details() {
        FieldConceptIdentity identity = fieldIdentity("com.acme.order.OrderHolder", "repository");
        ConceptCatalogEntry entry = new ConceptCatalogEntry(
                "test-provider",
                identity,
                "legacy-display-value",
                "com.acme",
                Optional.of("com.acme.order.OrderHolder"),
                ConceptAuthority.SYNTAX_DECLARED,
                Set.of(identity),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new FieldConceptDetails(new NamedTypeReference(
                        "CustomerRepository",
                        "CustomerRepository",
                        Optional.of(new JavaTypeIdentity("com.acme.customer", "CustomerRepository")),
                        true))));

        assertThat(matches(entry, new ConceptSearchTerm("customer", ConceptMatchMode.TOKEN_EXACT))).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("typedIdentitySearchCases")
    void should_project_search_tokens_from_typed_identity_and_mapper_mapping(
            String ignoredCase,
            ConceptCatalogEntry entry,
            ConceptSearchTerm term) {
        assertThat(matches(entry, term)).isTrue();
    }

    private static Stream<Arguments> typedIdentitySearchCases() {
        MethodTarget handlerTarget = methodTarget(
                "com.acme.messaging.OrderMessageHandler", "handleOrder", List.of("com.acme.messaging.OrderEvent"));
        MapperStatementConceptIdentity mapperIdentity = new MapperStatementConceptIdentity(
                new MapperStatementKey("com.acme.mapper.OrderMapper", "findByCustomer"));
        MethodTarget mappedMethod = methodTarget(
                "com.acme.mapper.OrderMapper", "findByCustomer", List.of("java.lang.String"));
        return Stream.of(
                Arguments.of(
                        "FIELD name",
                        entry(fieldIdentity("com.acme.order.OrderHolder", "customer")),
                        new ConceptSearchTerm("customer", ConceptMatchMode.TOKEN_EXACT)),
                Arguments.of(
                        "METHOD parameter",
                        entry(new MethodConceptIdentity(methodTarget(
                                "com.acme.order.OrderService", "find", List.of("com.acme.customer.Customer")))),
                        new ConceptSearchTerm("customer", ConceptMatchMode.TOKEN_EXACT)),
                Arguments.of(
                        "MQ handler and broker",
                        entry(new MqDestinationConceptIdentity(handlerTarget, MqBroker.KAFKA, "orders.created")),
                        new ConceptSearchTerm("kafka", ConceptMatchMode.TOKEN_EXACT)),
                Arguments.of(
                        "SCHEDULE trigger and handler",
                        entry(new ScheduleConceptIdentity(
                                handlerTarget,
                                ScheduleTriggerKind.CRON,
                                Optional.of("0 0 * * * *"))),
                        new ConceptSearchTerm("handle", ConceptMatchMode.TOKEN_EXACT)),
                Arguments.of(
                        "MAPPER mapped Java method",
                        mapperEntry(mapperIdentity, mappedMethod),
                        new ConceptSearchTerm("customer", ConceptMatchMode.TOKEN_EXACT)));
    }

    private static ConceptCatalogEntry entry(String displayValue) {
        MethodConceptIdentity identity = new MethodConceptIdentity(methodTarget(
                "com.acme.order.OrderService", "createOrder", List.of("java.lang.String")));
        return new ConceptCatalogEntry(
                "test-provider",
                identity,
                displayValue,
                "com.acme.order",
                Optional.of("com.acme.order.OrderService"),
                ConceptAuthority.SYNTAX_DECLARED,
                Set.of(identity));
    }

    private boolean matches(ConceptCatalogEntry entry, ConceptSearchTerm term) {
        return matcher.matches(projector.project(entry), List.of(term));
    }

    private static ConceptCatalogEntry entry(ConceptIdentity identity) {
        return new ConceptCatalogEntry(
                "test-provider",
                identity,
                "legacy-display-value",
                "com.acme",
                Optional.empty(),
                ConceptAuthority.SYNTAX_DECLARED,
                Set.of(identity));
    }

    private static ConceptCatalogEntry mapperEntry(
            MapperStatementConceptIdentity identity,
            MethodTarget mappedMethod) {
        MapperStatementMethodMapping mapping = MapperStatementMethodMapping.fromDeclarations(
                identity,
                1,
                false,
                List.of(mappedMethod));
        return new ConceptCatalogEntry(
                "test-provider",
                identity,
                "legacy-display-value",
                "",
                Optional.of(identity.statementKey().namespace()),
                ConceptAuthority.SYNTAX_DECLARED,
                Set.of(identity),
                Optional.empty(),
                Optional.empty(),
                Optional.of(mapping),
                Optional.empty());
    }

    private static MethodTarget methodTarget(String fullyQualifiedType, String methodName, List<String> parameterTypes) {
        return new MethodTarget(sourceType(fullyQualifiedType), methodName, parameterTypes);
    }

    private static FieldConceptIdentity fieldIdentity(String fullyQualifiedType, String fieldName) {
        return new FieldConceptIdentity(new TypeMember(sourceType(fullyQualifiedType), fieldName));
    }

    private static SourceTypeIdentity sourceType(String fullyQualifiedType) {
        int delimiter = fullyQualifiedType.lastIndexOf('.');
        return new SourceTypeIdentity(
                new JavaTypeIdentity(
                        fullyQualifiedType.substring(0, delimiter),
                        fullyQualifiedType.substring(delimiter + 1)),
                "src/main/java/" + fullyQualifiedType.replace('.', '/') + ".java");
    }
}
