package com.java.semantic.syntax.adapter.jdt;

import java.util.List;

import com.java.semantic.syntax.domain.ClassMetadata;
import com.java.semantic.syntax.domain.EntryPointMethod;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.ClassMetadata.MethodSignature;
import com.java.semantic.syntax.domain.ClassMetadata.SqlSource;
import com.java.semantic.syntax.domain.ClassMetadata.TypeKind;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.tuple;

import static org.assertj.core.api.Assertions.assertThat;

/** 型別 metadata 與 MyBatis SQL 抽取規則 */
class ClassMetadataExtractorTest {

    private final List<ClassMetadata> classes = SyntaxFixtures.extractSyntaxFixture().classes();

    private final List<ClassMetadata> multiModuleClasses = SyntaxFixtures.extractMultiModuleFixture().classes();

    // --- MyBatis SQL ---

    @Test
    void should_prefer_annotation_sql_over_xml_when_both_declare_the_same_statement() {
        MethodSignature method = methodOf(multiModuleClasses,
                "com.example.persistence.OrderMapper", "findByOrderNo");

        assertThat(method.sqlSource()).isEqualTo(SqlSource.ANNOTATION);
        assertThat(method.sql())
                .as("fixture 的 XML 已改成 xml_marker，優先序反轉時這個斷言會變紅")
                .isEqualTo("SELECT * FROM orders WHERE order_no = #{orderNo}")
                .doesNotContain("xml_marker");
    }

    @Test
    void should_fall_back_to_xml_when_a_mapper_method_has_no_sql_annotation() {
        MethodSignature method = methodOf(multiModuleClasses,
                "com.example.persistence.OrderMapper", "xmlOnly");

        assertThat(method.sqlSource()).isEqualTo(SqlSource.MAPPER_XML);
        assertThat(method.sql()).isEqualTo("SELECT * FROM orders WHERE customer_id = #{customerId}");
    }

    @Test
    void should_read_the_value_attribute_form_when_a_select_is_written_with_a_named_attribute() {
        assertThat(methodOf(classes, "com.example.syntax.AccountMapper", "findByCode").sql())
                .as("舊分析器只讀 single-member 形式，@Select(value=...) 會得到 null")
                .isEqualTo("SELECT * FROM accounts WHERE code = #{code}");
    }

    @Test
    void should_join_the_elements_when_a_select_is_written_as_a_string_array() {
        assertThat(methodOf(classes, "com.example.syntax.AccountMapper", "findByStatus").sql())
                .as("舊分析器會得到字面的大括號字串")
                .isEqualTo("SELECT * FROM accounts WHERE status = #{status}");
    }

    @Test
    void should_include_the_enclosing_type_when_a_nested_mapper_is_qualified() {
        assertThat(classes)
                .as("舊分析器產出 com.example.syntax.Nested，永遠對不上 XML namespace")
                .anyMatch(metadata -> "com.example.syntax.AccountMapper.Nested"
                        .equals(metadata.fullyQualifiedName()));
    }

    @Test
    void should_leave_sql_source_unset_when_a_method_has_no_sql_at_all() {
        MethodSignature method = methodOf(classes, "com.example.syntax.AccountShapes", "name");

        assertThat(method.sql()).isNull();
        assertThat(method.sqlSource()).isNull();
    }

    // --- 型別形狀 ---

    @Test
    void should_classify_records_and_enums_when_a_file_declares_several_type_kinds() {
        assertThat(classOf(classes, "com.example.syntax.AccountSummary").kind()).isEqualTo(TypeKind.RECORD);
        assertThat(classOf(classes, "com.example.syntax.AccountStatus").kind()).isEqualTo(TypeKind.ENUM);
        assertThat(classOf(classes, "com.example.syntax.AccountMapper").kind()).isEqualTo(TypeKind.INTERFACE);
        assertThat(classOf(classes, "com.example.syntax.AccountShapes").kind()).isEqualTo(TypeKind.CLASS);
    }

    @Test
    void should_expose_record_components_as_fields_when_a_record_is_scanned() {
        assertThat(classOf(classes, "com.example.syntax.AccountSummary").fields())
                .extracting(ClassMetadata.FieldInfo::name, ClassMetadata.FieldInfo::type)
                .containsExactly(
                        tuple("accountNo", "String"),
                        tuple("total", "long"));
    }

    @Test
    void should_detect_fluent_accessors_when_the_class_declares_accessors_fluent_true() {
        assertThat(classOf(classes, "com.example.syntax.AccountShapes").hasFluentAccessors())
                .as("舊分析器比對 pretty print 後的字串是否等於 true")
                .isTrue();
    }

    @Test
    void should_read_every_profile_when_the_profile_annotation_declares_an_array() {
        assertThat(classOf(classes, "com.example.syntax.AccountShapes").profiles())
                .containsExactly("dev", "uat");
    }

    @Test
    void should_keep_the_short_annotation_name_when_the_source_writes_it_short() {
        assertThat(classOf(classes, "com.example.syntax.AccountShapes").annotations())
                .contains("Service", "Profile", "Accessors");
    }

    @Test
    void should_keep_the_long_annotation_name_when_the_source_writes_it_fully_qualified() {
        assertThat(classOf(classes, "com.example.syntax.FullyQualifiedEndpoint").annotations())
                .as("metadata 保留寫法原貌；改用 simpleNameOf 縮短時這個斷言才會變紅")
                .contains("org.springframework.stereotype.Service",
                        "org.springframework.web.bind.annotation.RequestMapping")
                .doesNotContain("Service", "RequestMapping");
    }

    @Test
    void should_keep_the_long_method_annotation_name_when_a_mapping_is_written_fully_qualified() {
        assertThat(methodOf(classes, "com.example.syntax.FullyQualifiedEndpoint", "ping").annotations())
                .containsExactly("org.springframework.web.bind.annotation.GetMapping");
    }

    @Test
    void should_record_one_based_line_numbers_when_a_method_is_scanned() {
        MethodSignature method = methodOf(classes, "com.example.syntax.AccountShapes", "name");

        assertThat(method.startLine()).isPositive();
        assertThat(method.endLine()).isGreaterThanOrEqualTo(method.startLine());
    }

    @Test
    void should_simplify_parameter_types_when_a_method_declares_qualified_or_generic_parameters() {
        assertThat(methodOf(classes, "com.example.syntax.AccountMapper", "updateStatus").paramTypes())
                .containsExactly("Long", "String");
    }

    @Test
    void should_include_every_module_when_the_repository_is_a_maven_aggregator() {
        assertThat(multiModuleClasses)
                .extracting(ClassMetadata::fullyQualifiedName)
                .contains("com.example.api.OrderMessageListener",
                        "com.example.service.OrderApplicationService",
                        "com.example.persistence.OrderMapper");
    }

    @Test
    void should_extract_a_single_module_repository_when_it_carries_no_build_file() {
        RepositorySyntax syntax = SyntaxFixtures.extract(SyntaxFixtures.SPRING_BASIC);

        assertThat(syntax.entryPoints())
                .as("JDT Core 的 ASTParser 不讀 pom；source root 由目錄結構決定")
                .flatExtracting(entry -> entry.methods().stream().map(EntryPointMethod::name).toList())
                .containsExactly("getBasic");
        assertThat(methodOf(syntax.classes(), "com.example.basic.BasicRepository", "findById").sql())
                .isEqualTo("SELECT name FROM basic_orders WHERE id = #{id}");
    }

    private ClassMetadata classOf(List<ClassMetadata> source, String fullyQualifiedName) {
        return source.stream()
                .filter(metadata -> fullyQualifiedName.equals(metadata.fullyQualifiedName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no class metadata " + fullyQualifiedName));
    }

    private MethodSignature methodOf(List<ClassMetadata> source, String fullyQualifiedName, String methodName) {
        return classOf(source, fullyQualifiedName).methods().stream()
                .filter(method -> methodName.equals(method.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no method " + fullyQualifiedName + "#" + methodName));
    }
}
