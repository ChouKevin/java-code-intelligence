package com.java.semantic.semantic.adapter.jdtls;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MethodSignaturesTest {

    @Test
    void should_treat_a_bare_name_as_having_no_parameters() {
        MethodSignatures.ParsedMethod parsed = MethodSignatures.parse("save");

        assertThat(parsed.methodName()).isEqualTo("save");
        assertThat(parsed.hasParameters()).isFalse();
        assertThat(parsed.parameterTypes()).isEmpty();
    }

    @Test
    void should_parse_an_empty_argument_list_as_zero_parameters() {
        MethodSignatures.ParsedMethod parsed = MethodSignatures.parse("processOrder()");

        assertThat(parsed.methodName()).isEqualTo("processOrder");
        assertThat(parsed.hasParameters()).isTrue();
        assertThat(parsed.parameterTypes()).isEmpty();
    }

    @Test
    void should_strip_the_trailing_return_type_from_a_jdt_symbol_name() {
        MethodSignatures.ParsedMethod parsed = MethodSignatures.parse("save(T) : void");

        assertThat(parsed.methodName()).isEqualTo("save");
        assertThat(parsed.parameterTypes()).containsExactly("T");
    }

    @Test
    void should_normalize_qualified_and_generic_parameter_types() {
        MethodSignatures.ParsedMethod parsed =
                MethodSignatures.parse("update(java.lang.Long, java.util.List<com.example.Order>)");

        assertThat(parsed.parameterTypes()).containsExactly("Long", "List");
    }

    @Test
    void should_not_split_on_commas_nested_inside_generics() {
        MethodSignatures.ParsedMethod parsed =
                MethodSignatures.parse("dispatch(Map<String, List<Order>>, Long)");

        assertThat(parsed.parameterTypes()).containsExactly("Map", "Long");
    }

    @Test
    void should_treat_varargs_as_an_array_parameter() {
        MethodSignatures.ParsedMethod parsed = MethodSignatures.parse("of(String...)");

        assertThat(parsed.parameterTypes()).containsExactly("String[]");
    }

    @Test
    void should_extract_the_bare_name_from_a_signed_symbol_name() {
        assertThat(MethodSignatures.bareName("save(T) : void")).isEqualTo("save");
        assertThat(MethodSignatures.bareName("getId() : Long")).isEqualTo("getId");
        assertThat(MethodSignatures.bareName("deleteById")).isEqualTo("deleteById");
    }

    @Test
    void should_reduce_a_type_name_to_its_simple_generic_free_form() {
        assertThat(MethodSignatures.typeName("AbstractCrudService<Order>")).isEqualTo("AbstractCrudService");
        assertThat(MethodSignatures.typeName("OrderCrudService")).isEqualTo("OrderCrudService");
    }

    @Test
    void should_keep_array_dimensions_while_stripping_the_package() {
        assertThat(MethodSignatures.normalizeType("com.example.Order[]")).isEqualTo("Order[]");
        assertThat(MethodSignatures.normalizeType("int[]")).isEqualTo("int[]");
    }

    @Test
    void should_split_top_level_parameters_without_breaking_nested_generics() {
        List<String> parts = MethodSignatures.splitParameters("Map<String, Order>, Long, List<Map<A, B>>");

        assertThat(parts).hasSize(3);
    }
}
