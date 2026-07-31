package com.java.semantic.syntax.application;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.syntax.application.ConceptIdentity.TypeConceptIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.TypeUsageConceptIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.TypeUsageSlot;
import com.java.semantic.syntax.domain.AnalysisTargetStatus;
import com.java.semantic.syntax.domain.AnnotationEvidence;
import com.java.semantic.syntax.domain.ApiEntryPoint;
import com.java.semantic.syntax.domain.ClassMetadata;
import com.java.semantic.syntax.domain.ClassMetadata.FieldInfo;
import com.java.semantic.syntax.domain.ClassMetadata.MethodSignature;
import com.java.semantic.syntax.domain.ClassMetadata.TypeKind;
import com.java.semantic.syntax.domain.EntryPointClass;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.MqBroker;
import com.java.semantic.syntax.domain.MqEntryPoint;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.ResolvedTypeIdentity;
import com.java.semantic.syntax.domain.ScheduleEntryPoint;
import com.java.semantic.syntax.domain.ScheduleTriggerKind;
import com.java.semantic.syntax.domain.SourceSlice;
import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.syntax.domain.SyntaxRange;
import com.java.semantic.syntax.domain.TypeReference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 結構化概念目錄投影規則 */
class StructuredConceptCatalogProjectorTest {

    private final StructuredConceptCatalogProjector projector = new StructuredConceptCatalogProjector();

    @Test
    void should_project_all_phase_one_kinds_with_typed_identity_merge_and_canonical_order() {
        StructuredConceptCatalog catalog = projector.project(repositorySyntax());

        assertThat(catalog.entries()).extracting(ConceptCatalogEntry::kind)
                .contains(ConceptKind.TYPE, ConceptKind.METHOD, ConceptKind.FIELD, ConceptKind.ANNOTATION_USAGE,
                        ConceptKind.TYPE_USAGE, ConceptKind.API_ROUTE, ConceptKind.MQ_DESTINATION, ConceptKind.SCHEDULE);
        assertThat(catalog.entries()).filteredOn(entry -> entry.kind() == ConceptKind.TYPE)
                .extracting(ConceptCatalogEntry::identity)
                .containsOnlyOnce(new TypeConceptIdentity(SOURCE_FILE, OWNER_TYPE));
        assertThat(catalog.entries()).filteredOn(entry -> entry.kind() == ConceptKind.API_ROUTE)
                .extracting(ConceptCatalogEntry::canonicalValue)
                .containsExactly("ALL /orders", "GET /orders", "POST /orders");
        assertThat(catalog.entries()).filteredOn(entry -> entry.kind() == ConceptKind.MQ_DESTINATION)
                .extracting(ConceptCatalogEntry::canonicalValue)
                .containsExactly("KAFKA:orders.created", "KAFKA:orders.retry");
        assertThat(catalog.entries()).filteredOn(entry -> entry.kind() == ConceptKind.SCHEDULE)
                .extracting(ConceptCatalogEntry::canonicalValue)
                .containsExactly("CRON:0 0 * * * *", "FIXED_DELAY:<unresolved>");
        assertThat(catalog.entries()).filteredOn(entry -> entry.kind() == ConceptKind.SCHEDULE
                        && entry.canonicalValue().contains("<unresolved>"))
                .extracting(ConceptCatalogEntry::authority)
                .containsExactly(ConceptAuthority.METADATA_VALUE_UNRESOLVED);
        assertThat(catalog.entries()).filteredOn(entry -> entry.target().isPresent()
                        && entry.target().orElseThrow().equals(target()))
                .extracting(ConceptCatalogEntry::kind)
                .contains(ConceptKind.METHOD, ConceptKind.API_ROUTE, ConceptKind.MQ_DESTINATION, ConceptKind.SCHEDULE);
        assertThat(catalog.issueSummaries()).containsExactly(
                new ConceptIssueSummary(ConceptIssueReason.MQ_DESTINATION_UNRESOLVED, 3),
                new ConceptIssueSummary(ConceptIssueReason.SCHEDULE_TRIGGER_VALUE_UNRESOLVED, 1));
        assertThat(catalog.entries()).isSortedAccordingTo(Comparator
                .comparing(ConceptCatalogEntry::kind)
                .thenComparing(ConceptCatalogEntry::canonicalValue)
                .thenComparing(entry -> entry.identity().canonicalOrderKey()));
    }

    @Test
    void should_merge_same_identity_with_different_presentation_independently_of_merge_order() {
        TypeConceptIdentity identity = new TypeConceptIdentity(SOURCE_FILE, OWNER_TYPE);
        TypeConceptIdentity additionalEvidence = new TypeConceptIdentity(
                "src/main/java/com/acme/order/OrderRecord.java", "com.acme.order.OrderRecord");
        ConceptCatalogEntry left = new ConceptCatalogEntry(
                "z-provider",
                identity,
                "Zulu value",
                "Zulu display",
                Set.of("zulu"),
                "com.acme.zulu",
                Optional.of("com.acme.zulu.ZuluType"),
                ConceptAuthority.SYNTAX_DECLARED,
                Set.of(identity));
        ConceptCatalogEntry right = new ConceptCatalogEntry(
                "a-provider",
                identity,
                "Alpha value",
                "Alpha display",
                Set.of("alpha"),
                "com.acme.alpha",
                Optional.of("com.acme.alpha.AlphaType"),
                ConceptAuthority.FRAMEWORK_METADATA,
                Set.of(identity, additionalEvidence));

        ConceptCatalogEntry mergedFromLeft = left.merge(right);
        ConceptCatalogEntry mergedFromRight = right.merge(left);

        assertThat(mergedFromLeft).isEqualTo(mergedFromRight);
        assertThat(mergedFromLeft)
                .extracting(
                        ConceptCatalogEntry::providerId,
                        ConceptCatalogEntry::canonicalValue,
                        ConceptCatalogEntry::displayValue,
                        ConceptCatalogEntry::packageName,
                        ConceptCatalogEntry::declaringType,
                        ConceptCatalogEntry::authority)
                .containsExactly(
                        "a-provider",
                        "Alpha value",
                        "Alpha display",
                        "com.acme.alpha",
                        Optional.of("com.acme.alpha.AlphaType"),
                        ConceptAuthority.SYNTAX_DECLARED);
        assertThat(mergedFromLeft.searchTokens()).containsExactly("alpha", "zulu");
        assertThat(mergedFromLeft.evidence()).containsExactly(identity, additionalEvidence);
    }

    @Test
    void should_project_only_binding_proven_implemented_and_extended_type_usages() {
        ClassMetadata existing = metadata();
        ClassMetadata metadata = new ClassMetadata(
                existing.className(), existing.packageName(), existing.fullyQualifiedName(), existing.sourceFile(),
                existing.kind(), existing.isAbstract(), List.of("OrderPort", "MissingPort"), List.of("BaseHandler"),
                existing.annotations(), existing.imports(), existing.fields(), existing.methods(),
                existing.hasFluentAccessors(), existing.hasChainedAccessors(), existing.profiles(), existing.range(),
                existing.source(), existing.primary(), existing.beanQualifiers(), existing.annotationEvidence(),
                List.of(
                        new TypeReference("OrderPort", "com.acme.order.OrderPort", List.of(), true),
                        new TypeReference("MissingPort", "", List.of(), false)),
                List.of(new TypeReference("BaseHandler", "com.acme.order.BaseHandler", List.of(), true)));

        StructuredConceptCatalog catalog = projector.project(new RepositorySyntax(List.of(), List.of(metadata)));

        assertThat(catalog.entries())
                .filteredOn(entry -> entry.identity() instanceof TypeUsageConceptIdentity)
                .extracting(entry -> (TypeUsageConceptIdentity) entry.identity())
                .filteredOn(identity -> identity.ownerDeclaration().displayValue().equals(OWNER_TYPE))
                .extracting(identity -> identity.usageLocation().slot(), TypeUsageConceptIdentity::resolvedType)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(TypeUsageSlot.IMPLEMENTED_TYPE, "com.acme.order.OrderPort"),
                        org.assertj.core.groups.Tuple.tuple(TypeUsageSlot.EXTENDED_TYPE, "com.acme.order.BaseHandler"));
    }

    private static RepositorySyntax repositorySyntax() {
        ClassMetadata metadata = metadata();
        EntryPointClass entryPoints = new EntryPointClass(
                "OrderHandler", "com.acme.order", "com/acme/order/OrderHandler.java", "", List.of(), List.of(
                        new ApiEntryPoint("handleOrder", "", "/orders", List.of("POST", "GET"), List.of(), resolved()),
                        new ApiEntryPoint("handleOrder", "", "/orders", List.of(ApiEntryPoint.ALL_METHODS), List.of(), resolved()),
                        new ApiEntryPoint("handleOrder", "", "/orders/${tenant}", List.of("GET"), List.of(), resolved()),
                        new MqEntryPoint("handleOrder", "", MqBroker.KAFKA,
                                List.of("orders.retry", "orders.created"), resolved()),
                        new MqEntryPoint("handleOrder", "", MqBroker.KAFKA, List.of(), resolved()),
                        new MqEntryPoint("handleOrder", "", MqBroker.KAFKA,
                                List.of("${orders.topic}", "#{topicProvider.topic}"), resolved()),
                        new ScheduleEntryPoint("handleOrder", "", ScheduleTriggerKind.CRON, "0 0 * * * *", resolved()),
                        new ScheduleEntryPoint("handleOrder", "", ScheduleTriggerKind.FIXED_DELAY, "#{delayProvider.value}", resolved())));
        return new RepositorySyntax(List.of(entryPoints), List.of(metadata, metadata));
    }

    private static ClassMetadata metadata() {
        MethodSignature method = new MethodSignature(
                "handleOrder",
                List.of("com.acme.order.OrderCommand"),
                List.of("Transactional"),
                "",
                null,
                1,
                2,
                range(),
                source(),
                List.of(new TypeReference("OrderCommand", "com.acme.order.OrderCommand", List.of(), true)),
                Optional.of(new TypeReference("OrderReceipt", "com.acme.order.OrderReceipt", List.of(), true)),
                List.of(),
                List.of(annotation("Transactional", "org.springframework.transaction.annotation.Transactional")),
                List.of(new ResolvedTypeIdentity("com.acme.order", "OrderEvent")),
                new SyntaxPosition(1, 1),
                resolved(),
                true,
                false,
                false);
        FieldInfo field = new FieldInfo(
                "orderState",
                "OrderState",
                List.of("Autowired"),
                "",
                new TypeReference("OrderState", "com.acme.order.OrderState", List.of(), true),
                List.of(annotation("Autowired", "org.springframework.beans.factory.annotation.Autowired")));
        return new ClassMetadata(
                "OrderHandler",
                "com.acme.order",
                OWNER_TYPE,
                SOURCE_FILE,
                TypeKind.CLASS,
                false,
                List.of(),
                List.of(),
                List.of("Service"),
                List.of(),
                List.of(field),
                List.of(method),
                false,
                false,
                List.of(),
                range(),
                source(),
                false,
                List.of(),
                List.of(annotation("Service", "org.springframework.stereotype.Service")));
    }

    private static AnnotationEvidence annotation(String writtenName, String resolvedType) {
        return new AnnotationEvidence(writtenName, Optional.of(new ResolvedTypeIdentity(
                resolvedType.substring(0, resolvedType.lastIndexOf('.')),
                resolvedType.substring(resolvedType.lastIndexOf('.') + 1))));
    }

    private static MethodTargetResolution resolved() {
        return MethodTargetResolution.resolved(target());
    }

    private static MethodTarget target() {
        return new MethodTarget(SOURCE_FILE, "com.acme.order", "OrderHandler", "handleOrder",
                List.of("com.acme.order.OrderCommand"));
    }

    private static SyntaxRange range() {
        return new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(0, 1));
    }

    private static SourceSlice source() {
        return new SourceSlice(range(), "x");
    }

    private static final String SOURCE_FILE = "src/main/java/com/acme/order/OrderHandler.java";

    private static final String OWNER_TYPE = "com.acme.order.OrderHandler";
}
