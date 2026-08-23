package com.java.semantic.config;

import com.java.semantic.callgraph.domain.ReadPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReadPolicyPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SemanticAnalysisConfiguration.class);

    @Test
    void should_default_all_deny_lists_to_empty_and_register_policy() {
        contextRunner.run(context -> {
            ReadPolicyProperties properties = context.getBean(ReadPolicyProperties.class);

            assertThat(properties.forbiddenRepositories()).hasSize(0);
            assertThat(properties.forbiddenPackages()).hasSize(0);
            assertThat(properties.forbiddenClasses()).hasSize(0);
            assertThat(properties.forbiddenMethods()).hasSize(0);
            assertThat(context).hasSingleBean(ReadPolicy.class);
        });
    }

    @Test
    void should_bind_deny_rules_from_semantic_analysis_read_policy_prefix() {
        contextRunner
                .withPropertyValues(
                        "semantic.analysis.read-policy.forbidden-repositories[0]=vault-repo",
                        "semantic.analysis.read-policy.forbidden-packages[0].repo-id=orders",
                        "semantic.analysis.read-policy.forbidden-packages[0].package-prefix=com.acme.secret",
                        "semantic.analysis.read-policy.forbidden-classes[0].repo-id=orders",
                        "semantic.analysis.read-policy.forbidden-classes[0].package-name=com.acme",
                        "semantic.analysis.read-policy.forbidden-classes[0].class-name=Outer.Inner",
                        "semantic.analysis.read-policy.forbidden-methods[0].repo-id=orders",
                        "semantic.analysis.read-policy.forbidden-methods[0].package-name=com.acme",
                        "semantic.analysis.read-policy.forbidden-methods[0].class-name=Vault",
                        "semantic.analysis.read-policy.forbidden-methods[0].method-name=read",
                        "semantic.analysis.read-policy.forbidden-methods[0].parameter-types[0]=java.lang.String")
                .run(context -> {
                    ReadPolicyProperties properties = context.getBean(ReadPolicyProperties.class);

                    assertThat(properties.forbiddenRepositories()).containsExactly("vault-repo");
                    assertThat(properties.forbiddenPackages()).containsExactly(
                            new ReadPolicyProperties.PackageRule("orders", "com.acme.secret"));
                    assertThat(properties.forbiddenClasses()).containsExactly(
                            new ReadPolicyProperties.ClassRule("orders", "com.acme", "Outer.Inner"));
                    assertThat(properties.forbiddenMethods()).containsExactly(
                            new ReadPolicyProperties.MethodRule(
                                    "orders",
                                    "com.acme",
                                    "Vault",
                                    "read",
                                    List.of("java.lang.String")));
                });
    }

    @Test
    void should_reject_blank_values_in_each_rule_scope() {
        assertInvalid("semantic.analysis.read-policy.forbidden-repositories[0]= ");
        assertInvalid(
                "semantic.analysis.read-policy.forbidden-packages[0].repo-id=orders",
                "semantic.analysis.read-policy.forbidden-packages[0].package-prefix= ");
        assertInvalid(
                "semantic.analysis.read-policy.forbidden-classes[0].repo-id=orders",
                "semantic.analysis.read-policy.forbidden-classes[0].package-name=com.acme",
                "semantic.analysis.read-policy.forbidden-classes[0].class-name= ");
        assertInvalid(
                "semantic.analysis.read-policy.forbidden-methods[0].repo-id=orders",
                "semantic.analysis.read-policy.forbidden-methods[0].package-name=com.acme",
                "semantic.analysis.read-policy.forbidden-methods[0].class-name=Vault",
                "semantic.analysis.read-policy.forbidden-methods[0].method-name=read",
                "semantic.analysis.read-policy.forbidden-methods[0].parameter-types[0]= ");
    }

    private void assertInvalid(String... propertyValues) {
        contextRunner
                .withPropertyValues(propertyValues)
                .run(context -> assertThat(context).hasFailed());
    }
}
