package com.java.semantic.syntax.adapter.jdt;

import java.util.List;

import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.syntax.domain.ApiEntryPoint;
import com.java.semantic.syntax.domain.EntryPointClass;
import com.java.semantic.syntax.domain.RepositorySyntax;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** API 抽取規則，這些規則在舊分析器裡沒有任何測試 */
class ApiExtractorTest {

    private final RepositorySyntax syntax = SyntaxFixtures.extractSyntaxFixture();

    private final List<EntryPointClass> classes = syntax.entryPoints();

    @Test
    void should_expose_the_resolved_canonical_target_for_an_api_entry_point_and_reuse_metadata_proof() {
        ApiEntryPoint api = apiOf("OrderApiController", "get");
        MethodTarget target = api.analysisTarget().target().orElseThrow();

        assertThat(target).isEqualTo(new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example.syntax", "OrderApiController"),
                        "src/main/java/com/example/syntax/OrderApiController.java"),
                "get",
                List.of("java.lang.Long")));
        assertThat(metadataTargetOf("OrderApiController", "get")).isSameAs(api.analysisTarget());
    }

    @Test
    void should_keep_nested_class_context_in_the_api_entry_point_target() {
        MethodTarget target = apiOf("OrderApiController.NestedController", "inner").analysisTarget()
                .target().orElseThrow();

        assertThat(target.className()).isEqualTo("OrderApiController.NestedController");
    }

    @Test
    void should_combine_a_constant_class_mapping_with_the_method_path_when_the_constant_lives_in_another_file() {
        assertThat(routesOf("OrderApiController")).contains("/const/{id}");
    }

    @Test
    void should_scan_patch_endpoints_when_a_method_is_annotated_with_patch_mapping() {
        assertThat(apiOf("OrderApiController", "patch").httpMethods()).containsExactly("PATCH");
    }

    @Test
    void should_emit_one_route_per_path_when_a_mapping_declares_several_paths() {
        assertThat(routesOf("OrderApiController"))
                .as("舊分析器會產出單筆 /const/a,/b")
                .contains("/const/a", "/const/b")
                .doesNotContain("/const/a,/b");
    }

    @Test
    void should_prefer_value_over_path_when_both_attributes_are_declared() {
        assertThat(apiOf("OrderApiController", "bothAttributes").apiUrl())
                .as("優先序由呼叫端宣告，不是原始碼書寫順序；舊分析器會取先寫的 path")
                .isEqualTo("/const/from-value");
    }

    @Test
    void should_read_both_verbs_when_request_mapping_declares_a_method_array() {
        assertThat(apiOf("OrderApiController", "verbs").httpMethods())
                .containsExactly("GET", "POST");
    }

    @Test
    void should_default_to_all_when_request_mapping_declares_no_method() {
        assertThat(apiOf("OrderApiController", "bothAttributes").httpMethods())
                .containsExactly(ApiEntryPoint.ALL_METHODS);
    }

    @Test
    void should_fold_a_constant_expression_when_the_path_constant_concatenates_literals() {
        assertThat(routesOf("OrderApiController")).contains("/const/a/b");
    }

    @Test
    void should_read_openapi_three_annotations_when_a_method_declares_operation() {
        assertThat(apiOf("OrderApiController", "remove").swaggerDescriptions())
                .as("@Operation 的 summary 先於 description，順序是原始碼順序而非 HashSet 順序")
                .containsExactly("刪除訂單", "依 id 刪除");
    }

    @Test
    void should_skip_deprecated_methods_when_a_mapping_is_annotated_deprecated() {
        assertThat(routesOf("OrderApiController")).doesNotContain("/const/legacy");
    }

    @Test
    void should_attribute_nested_methods_to_the_nested_class_when_a_controller_declares_an_inner_controller() {
        assertThat(routesOf("OrderApiController"))
                .as("巢狀方法不該被外層重複計算")
                .doesNotContain("/const/inner");
        assertThat(routesOf("OrderApiController.NestedController")).containsExactly("/nested/inner");
    }

    @Test
    void should_keep_plain_request_mapping_classes_when_no_stereotype_annotation_is_present() {
        assertThat(routesOf("PlainMappingEndpoint"))
                .as("要求 @RestController 會誤殺今天可用的純 @RequestMapping 類別")
                .containsExactly("/plain/ping");
    }

    @Test
    void should_still_match_a_mapping_when_the_annotation_is_written_fully_qualified() {
        assertThat(routesOf("FullyQualifiedEndpoint"))
                .as("比對只看最後一段；改用完整名稱比對時 FQN 寫法會失配，controller 整個消失")
                .containsExactly("/fqn/ping");
    }

    @Test
    void should_read_the_http_verb_when_the_mapping_annotation_is_written_fully_qualified() {
        assertThat(apiOf("FullyQualifiedEndpoint", "ping").httpMethods()).containsExactly("GET");
    }

    @Test
    void should_produce_no_entry_point_when_a_record_or_an_enum_declares_a_mapping_method() {
        assertThat(classes.stream().map(EntryPointClass::className))
                .as("record 與 enum 不會是 controller、listener 或排程宿主")
                .doesNotContain("AccountSummary", "AccountStatus");
    }

    @Test
    void should_exclude_feign_clients_when_an_interface_declares_feign_client() {
        assertThat(classes)
                .as("Feign 是出站呼叫，不是入站端點")
                .noneMatch(entry -> "RemoteOrderClient".equals(entry.className()));
    }

    @Test
    void should_skip_the_whole_file_when_any_type_in_it_is_a_controller_advice() {
        assertThat(classes.stream().map(EntryPointClass::className))
                .doesNotContain("GlobalExceptionAdvice", "CoLocatedEndpoint");
    }

    @Test
    void should_expose_the_class_level_mapping_as_the_base_path_when_a_controller_declares_one() {
        assertThat(classOf("OrderApiController").basePaths()).containsExactly("/const");
    }

    @Test
    void should_carry_the_method_javadoc_when_a_mapping_method_is_documented() {
        assertThat(apiOf("OrderApiController", "get").description()).isEqualTo("取得單筆訂單");
    }

    @Test
    void should_record_the_source_relative_file_path_when_a_class_is_scanned() {
        assertThat(classOf("OrderApiController").packagePath())
                .isEqualTo("com/example/syntax/OrderApiController.java");
    }

    private List<String> routesOf(String className) {
        return classes.stream()
                .filter(entry -> className.equals(entry.className()))
                .flatMap(entry -> entry.methods().stream())
                .filter(ApiEntryPoint.class::isInstance)
                .map(ApiEntryPoint.class::cast)
                .map(ApiEntryPoint::apiUrl)
                .toList();
    }

    private ApiEntryPoint apiOf(String className, String methodName) {
        return classes.stream()
                .filter(entry -> className.equals(entry.className()))
                .flatMap(entry -> entry.methods().stream())
                .filter(ApiEntryPoint.class::isInstance)
                .map(ApiEntryPoint.class::cast)
                .filter(api -> methodName.equals(api.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no API entry point " + className + "#" + methodName));
    }

    private EntryPointClass classOf(String className) {
        return classes.stream()
                .filter(entry -> className.equals(entry.className()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no entry point class " + className));
    }

    private Object metadataTargetOf(String className, String methodName) {
        return syntax.sourceTypes().stream()
                .filter(metadata -> className.equals(metadata.declaration().identity().javaType().className()))
                .flatMap(metadata -> metadata.members().methods().stream())
                .filter(method -> methodName.equals(method.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no metadata method " + className + "#" + methodName))
                .analysisTarget();
    }
}
