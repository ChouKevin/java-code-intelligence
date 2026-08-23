package com.java.semantic.syntax.application;

import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.callgraph.domain.EvidenceVisibility;
import com.java.semantic.callgraph.domain.ReadPolicy;
import com.java.semantic.callgraph.domain.TypeId;
import com.java.semantic.config.ConfiguredReadPolicy;
import com.java.semantic.config.ReadPolicyProperties;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.syntax.domain.ApiEntryPoint;
import com.java.semantic.syntax.domain.SourceTypeMetadata;
import com.java.semantic.syntax.domain.SourceTypeKind;
import com.java.semantic.syntax.domain.EntryPointClass;
import com.java.semantic.syntax.domain.EntryPointMethod;
import com.java.semantic.syntax.domain.EntryPointType;
import com.java.semantic.syntax.domain.MqBroker;
import com.java.semantic.syntax.domain.MqEntryPoint;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.ScheduleEntryPoint;
import com.java.semantic.syntax.domain.ScheduleTriggerKind;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EntryPointDiscoveryFilterTest {

    private static final RepositoryId ORDERS = RepositoryId.of("orders");

    @Mock
    private ReadPolicy readPolicy;

    private EntryPointDiscoveryFilter filter;
    private SourceTypeMetadata secretSourceTypeMetadata;

    @BeforeEach
    void setUp() {
        filter = new EntryPointDiscoveryFilter(readPolicy);
        secretSourceTypeMetadata = metadata("SecretMetadata", "secret metadata source");
    }

    @Test
    void should_return_empty_syntax_when_repository_is_forbidden() {
        RepositorySyntax syntax = new RepositorySyntax(
                List.of(entryPointClass(new ApiEntryPoint(
                        "read", "secret", "/read", List.of("GET"), List.of("secret"), unresolved()))),
                List.of(secretSourceTypeMetadata));
        when(readPolicy.visibilityOfRepository("orders"))
                .thenReturn(EvidenceVisibility.BUSINESS_READ_FORBIDDEN);

        RepositorySyntax filtered = filter.filter(
                ORDERS, syntax, EnumSet.allOf(EntryPointType.class));

        assertThat(filtered).isEqualTo(RepositorySyntax.empty());
    }

    @Test
    void should_drop_entry_point_class_when_package_or_class_identity_is_forbidden() {
        ReadPolicy configuredPolicy = new ConfiguredReadPolicy(new ReadPolicyProperties(
                List.of(),
                List.of(new ReadPolicyProperties.PackageRule("orders", "com.acme.secret")),
                List.of(new ReadPolicyProperties.ClassRule("orders", "com.acme.publicapi", "HiddenClass")),
                List.of()));
        EntryPointDiscoveryFilter configuredFilter = new EntryPointDiscoveryFilter(configuredPolicy);
        RepositorySyntax syntax = new RepositorySyntax(
                List.of(
                        entryPointClass(
                                "com.acme.secret.child",
                                "PackageHidden",
                                new ApiEntryPoint(
                                        "packageRead", "", "/package", List.of("GET"), List.of(), unresolved())),
                        entryPointClass(
                                "com.acme.publicapi",
                                "HiddenClass",
                                new ApiEntryPoint(
                                        "classRead", "", "/class", List.of("GET"), List.of(), unresolved()))),
                List.of(secretSourceTypeMetadata));

        RepositorySyntax filtered = configuredFilter.filter(
                ORDERS, syntax, EnumSet.of(EntryPointType.API));

        assertThat(filtered.entryPoints()).isEmpty();
        assertThat(filtered.sourceTypes()).isEmpty();
    }

    @Test
    void should_fail_closed_for_every_same_name_method_when_exact_overload_is_forbidden() {
        ReadPolicy configuredPolicy = new ConfiguredReadPolicy(new ReadPolicyProperties(
                List.of(),
                List.of(),
                List.of(),
                List.of(new ReadPolicyProperties.MethodRule(
                        "orders", "com.acme", "EntryPointController", "read", List.of("String")))));
        EntryPointDiscoveryFilter configuredFilter = new EntryPointDiscoveryFilter(configuredPolicy);
        RepositorySyntax syntax = new RepositorySyntax(
                List.of(entryPointClass(
                        new ApiEntryPoint("read", "", "/read-one", List.of("GET"), List.of(), unresolved()),
                        new ApiEntryPoint("read", "", "/read-two", List.of("POST"), List.of(), unresolved()),
                        new ApiEntryPoint("write", "", "/write", List.of("POST"), List.of(), unresolved()))),
                List.of());

        RepositorySyntax filtered = configuredFilter.filter(
                ORDERS, syntax, EnumSet.of(EntryPointType.API));

        assertThat(filtered.entryPoints()).singleElement().satisfies(entryPoint ->
                assertThat(entryPoint.methods())
                        .extracting(EntryPointMethod::name)
                        .containsExactly("write"));
    }

    @Test
    void should_filter_denied_and_unrequested_methods_without_reordering_survivors() {
        RepositorySyntax syntax = new RepositorySyntax(
                List.of(entryPointClass(
                        new ApiEntryPoint(
                                "read", "secret", "/read", List.of("GET"), List.of("secret"), unresolved()),
                        new MqEntryPoint("consume", "allowed", MqBroker.KAFKA, List.of("orders"), unresolved()),
                        new ScheduleEntryPoint(
                                "refresh", "allowed", ScheduleTriggerKind.CRON, "0 * * * * *", unresolved()))),
                List.of(secretSourceTypeMetadata));
        when(readPolicy.visibilityOfRepository("orders")).thenReturn(EvidenceVisibility.READABLE);
        when(readPolicy.visibilityOf(any(TypeId.class))).thenReturn(EvidenceVisibility.READABLE);
        when(readPolicy.visibilityOfDiscoveredMethod(any(TypeId.class), eq("read")))
                .thenReturn(EvidenceVisibility.BUSINESS_READ_FORBIDDEN);
        when(readPolicy.visibilityOfDiscoveredMethod(any(TypeId.class), eq("consume")))
                .thenReturn(EvidenceVisibility.READABLE);
        when(readPolicy.visibilityOfDiscoveredMethod(any(TypeId.class), eq("refresh")))
                .thenReturn(EvidenceVisibility.READABLE);

        RepositorySyntax filtered = filter.filter(
                RepositoryId.of("orders"), syntax, EnumSet.of(EntryPointType.MQ, EntryPointType.SCHEDULE));

        assertThat(filtered.entryPoints()).singleElement().satisfies(entryPoint ->
                assertThat(entryPoint.methods())
                        .extracting(EntryPointMethod::name)
                        .containsExactly("consume", "refresh"));
        assertThat(filtered.sourceTypes()).isEmpty();
        assertThat(filtered.toString()).doesNotContain("secret");
    }

    @Test
    void should_drop_class_when_no_method_survives() {
        RepositorySyntax syntax = new RepositorySyntax(
                List.of(entryPointClass(new ApiEntryPoint(
                        "read", "", "/read", List.of("GET"), List.of(), unresolved()))),
                List.of());
        when(readPolicy.visibilityOfRepository("orders")).thenReturn(EvidenceVisibility.READABLE);
        when(readPolicy.visibilityOf(any(TypeId.class))).thenReturn(EvidenceVisibility.READABLE);

        RepositorySyntax filtered = filter.filter(
                ORDERS, syntax, EnumSet.of(EntryPointType.MQ));

        assertThat(filtered.entryPoints()).isEmpty();
        assertThat(filtered.sourceTypes()).isEmpty();
    }

    @Test
    void should_remove_all_class_metadata_from_public_discovery_result() {
        RepositorySyntax syntax = new RepositorySyntax(
                List.of(entryPointClass(new ApiEntryPoint(
                        "allowed", "", "/allowed", List.of("GET"), List.of(), unresolved()))),
                List.of(secretSourceTypeMetadata));
        when(readPolicy.visibilityOfRepository("orders")).thenReturn(EvidenceVisibility.READABLE);
        when(readPolicy.visibilityOf(any(TypeId.class))).thenReturn(EvidenceVisibility.READABLE);
        when(readPolicy.visibilityOfDiscoveredMethod(any(TypeId.class), eq("allowed")))
                .thenReturn(EvidenceVisibility.READABLE);

        RepositorySyntax filtered = filter.filter(
                ORDERS, syntax, EnumSet.allOf(EntryPointType.class));

        assertThat(filtered.entryPoints()).hasSize(1);
        assertThat(filtered.sourceTypes()).isEmpty();
        assertThat(filtered.toString()).doesNotContain("secret metadata source", "SecretMetadata");
    }

    private static EntryPointClass entryPointClass(EntryPointMethod... methods) {
        return entryPointClass("com.acme", "EntryPointController", methods);
    }

    private static MethodTargetResolution unresolved() {
        return MethodTargetResolution.unresolved("TEST_ANALYSIS_TARGET_UNAVAILABLE");
    }

    private static EntryPointClass entryPointClass(
            String packageName,
            String className,
            EntryPointMethod... methods) {
        return new EntryPointClass(
                new SourceTypeIdentity(
                        new JavaTypeIdentity(packageName, className),
                        packageName.replace('.', '/') + "/" + className + ".java"),
                "",
                List.of(),
                List.of(methods));
    }

    private static SourceTypeMetadata metadata(String className, String source) {
        SyntaxRange range = new SyntaxRange(
                new SyntaxPosition(0, 0),
                new SyntaxPosition(0, source.length()));
        return com.java.semantic.syntax.domain.SourceTypeMetadataFixture.sourceType(
                className,
                "com.acme.secret",
                "com.acme.secret." + className,
                "com/acme/secret/" + className + ".java",
                SourceTypeKind.CLASS,
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                false,
                false,
                List.of(),
                range,
                new SourceRange("com/acme/secret/" + className + ".java", range),
                false,
                List.of());
    }
}
