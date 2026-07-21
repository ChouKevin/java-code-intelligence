package com.java.semantic.config;

import com.java.semantic.callgraph.domain.EvidenceVisibility;
import com.java.semantic.callgraph.domain.MethodId;
import com.java.semantic.callgraph.domain.ReadPolicy;
import com.java.semantic.callgraph.domain.TypeId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfiguredReadPolicyTest {

    @Test
    void should_forbid_each_scope_when_repository_package_class_or_exact_method_matches() {
        ReadPolicy repositoryPolicy = policy(new ReadPolicyProperties(
                List.of("vault-repo"), List.of(), List.of(), List.of()));
        ReadPolicy packagePolicy = policy(new ReadPolicyProperties(
                List.of(),
                List.of(new ReadPolicyProperties.PackageRule("orders", "com.acme.secret")),
                List.of(),
                List.of()));
        ReadPolicy classPolicy = policy(new ReadPolicyProperties(
                List.of(),
                List.of(),
                List.of(new ReadPolicyProperties.ClassRule("orders", "com.acme", "Outer")),
                List.of()));
        ReadPolicy methodPolicy = policy(new ReadPolicyProperties(
                List.of(),
                List.of(),
                List.of(),
                List.of(new ReadPolicyProperties.MethodRule(
                        "orders", "com.acme", "Vault", "read", List.of("String")))));

        assertThat(repositoryPolicy.visibilityOfRepository("vault-repo"))
                .isEqualTo(EvidenceVisibility.BUSINESS_READ_FORBIDDEN);
        assertThat(repositoryPolicy.visibilityOf(method(
                "vault-repo", "com.acme", "Vault", "read", List.of())))
                .isEqualTo(EvidenceVisibility.BUSINESS_READ_FORBIDDEN);
        assertThat(packagePolicy.visibilityOf(method(
                "orders", "com.acme.secret.child", "Vault", "read", List.of())))
                .isEqualTo(EvidenceVisibility.BUSINESS_READ_FORBIDDEN);
        assertThat(classPolicy.visibilityOf(method(
                "orders", "com.acme", "Outer", "read", List.of())))
                .isEqualTo(EvidenceVisibility.BUSINESS_READ_FORBIDDEN);
        assertThat(methodPolicy.visibilityOf(method(
                "orders", "com.acme", "Vault", "read", List.of("String"))))
                .isEqualTo(EvidenceVisibility.BUSINESS_READ_FORBIDDEN);
    }

    @Test
    void should_match_package_only_at_segment_boundaries_and_class_by_exact_identity() {
        ReadPolicy readPolicy = policy(new ReadPolicyProperties(
                List.of(),
                List.of(new ReadPolicyProperties.PackageRule("orders", "com.acme.secret")),
                List.of(new ReadPolicyProperties.ClassRule("orders", "com.acme", "Outer")),
                List.of()));

        assertThat(readPolicy.visibilityOf(method(
                "orders", "com.acme.secretive", "Vault", "read", List.of())))
                .isEqualTo(EvidenceVisibility.READABLE);
        assertThat(readPolicy.visibilityOf(method(
                "orders", "com.acme", "Outermost", "read", List.of())))
                .isEqualTo(EvidenceVisibility.READABLE);
        assertThat(readPolicy.visibilityOf(method(
                "orders", "com.acme", "Outer.Inner", "read", List.of())))
                .isEqualTo(EvidenceVisibility.READABLE);
        assertThat(readPolicy.visibilityOf(method(
                "catalog", "com.acme.secret", "Outer.Inner", "read", List.of())))
                .isEqualTo(EvidenceVisibility.READABLE);
    }

    @Test
    void should_keep_other_overload_readable_when_method_parameters_differ() {
        ReadPolicy readPolicy = policy(new ReadPolicyProperties(
                List.of(),
                List.of(),
                List.of(),
                List.of(new ReadPolicyProperties.MethodRule(
                        "orders", "com.acme", "Vault", "read", List.of("String")))));

        assertThat(readPolicy.visibilityOf(method(
                "orders", "com.acme", "Vault", "read", List.of("Long"))))
                .isEqualTo(EvidenceVisibility.READABLE);
    }

    @Test
    void should_hide_every_same_name_discovery_when_exact_overload_rule_cannot_be_distinguished() {
        ReadPolicy readPolicy = policy(new ReadPolicyProperties(
                List.of(),
                List.of(),
                List.of(),
                List.of(new ReadPolicyProperties.MethodRule(
                        "orders", "com.acme", "Vault", "read", List.of("String")))));
        TypeId typeId = new TypeId("orders", "com.acme", "Vault");

        assertThat(readPolicy.visibilityOfDiscoveredMethod(typeId, "read"))
                .isEqualTo(EvidenceVisibility.BUSINESS_READ_FORBIDDEN);
        assertThat(readPolicy.visibilityOfDiscoveredMethod(typeId, "write"))
                .isEqualTo(EvidenceVisibility.READABLE);
    }

    @Test
    void should_match_nested_exact_method_rule_after_canonicalizing_generic_and_varargs_parameters() {
        ReadPolicy readPolicy = policy(new ReadPolicyProperties(
                List.of(),
                List.of(),
                List.of(),
                List.of(new ReadPolicyProperties.MethodRule(
                        "orders", "com.acme", "Outer.Inner", "read", List.of("List", "String[]")))));

        assertThat(readPolicy.visibilityOf(method(
                "orders", "com.acme", "Outer$Inner", "read",
                List.of("java.util.List<com.acme.Secret>", "java.lang.String..."))))
                .isEqualTo(EvidenceVisibility.BUSINESS_READ_FORBIDDEN);
    }

    @Test
    void should_apply_only_repository_package_and_class_rules_to_type_identity() {
        ReadPolicy readPolicy = policy(new ReadPolicyProperties(
                List.of(),
                List.of(),
                List.of(),
                List.of(new ReadPolicyProperties.MethodRule(
                        "orders", "com.acme", "Vault", "typeEvidence", List.of()))));

        assertThat(readPolicy.visibilityOf(new TypeId("orders", "com.acme", "Vault")))
                .isEqualTo(EvidenceVisibility.READABLE);
    }

    @Test
    void should_canonicalize_nested_class_rules_for_type_identity() {
        ReadPolicy readPolicy = policy(new ReadPolicyProperties(
                List.of(),
                List.of(),
                List.of(new ReadPolicyProperties.ClassRule("orders", "com.acme", "Outer$Inner")),
                List.of()));

        assertThat(readPolicy.visibilityOf(new TypeId("orders", "com.acme", "Outer.Inner")))
                .isEqualTo(EvidenceVisibility.BUSINESS_READ_FORBIDDEN);
    }

    private static ReadPolicy policy(ReadPolicyProperties properties) {
        return new ConfiguredReadPolicy(properties);
    }

    private static MethodId method(
            String repoId,
            String packageName,
            String className,
            String methodName,
            List<String> parameterTypes) {
        return new MethodId(repoId, packageName, className, methodName, parameterTypes);
    }
}
