package com.java.semantic.api;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;
import com.java.semantic.api.ApiMonitoringRenderer.RenderedApiMonitoring;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiMonitoringRendererTest {

    private final ApiMonitoringRenderer renderer = new ApiMonitoringRenderer();

    @Test
    void should_render_only_explicitly_allowlisted_record_components() {
        RenderedApiMonitoring rendered = renderer.render("request", new RequestPayload(
                "repo-42",
                List.of("one", "two"),
                "four",
                new NestedPayload("revision-7"),
                "private source content"));

        String output = String.join("\n", rendered.segments());
        assertThat(output).contains("request.repositoryId=repo-42");
        assertThat(output).contains("request.labels.size=2");
        assertThat(output).contains("request.description.size=4");
        assertThat(output).contains("request.nested.revision=revision-7");
        assertThat(output).doesNotContain("private source content");
        assertThat(rendered.monitoringFieldsTruncated()).isFalse();
    }

    @Test
    void should_emit_nothing_for_a_null_nested_component() {
        RenderedApiMonitoring rendered = renderer.render("request", new NullableNestedPayload(null));

        assertThat(rendered.segments()).isEmpty();
        assertThat(rendered.monitoringFieldsTruncated()).isFalse();
    }

    @Test
    void should_emit_nothing_for_raw_null_value_or_size_components() {
        RenderedApiMonitoring rendered = renderer.render(
                "response", new NullableScalarPayload(null, null));

        assertThat(rendered.segments()).isEmpty();
        assertThat(rendered.monitoringFieldsTruncated()).isFalse();
    }

    @Test
    void should_emit_nothing_for_an_empty_optional_nested_component() {
        RenderedApiMonitoring rendered = renderer.render(
                "request", new OptionalNestedPayload(Optional.empty()));

        assertThat(rendered.segments()).isEmpty();
        assertThat(rendered.monitoringFieldsTruncated()).isFalse();
    }

    @Test
    void should_render_present_optional_scalars_and_omit_empty_optional_scalars() {
        RenderedApiMonitoring present = renderer.render(
                "response", new OptionalScalarPayload(Optional.of(7), Optional.of("MAPPER_XML_ELEMENT")));
        RenderedApiMonitoring empty = renderer.render(
                "response", new OptionalScalarPayload(Optional.empty(), Optional.empty()));

        assertThat(String.join("\n", present.segments()))
                .contains("response.documentOrdinal=7")
                .contains("response.representation=MAPPER_XML_ELEMENT");
        assertThat(empty.segments()).isEmpty();
    }

    @Test
    void should_unwrap_and_render_a_present_optional_nested_component() {
        RenderedApiMonitoring rendered = renderer.render(
                "request", new OptionalNestedPayload(Optional.of(new NestedPayload("revision-8"))));

        assertThat(String.join("\n", rendered.segments()))
                .contains("request.nested.revision=revision-8");
    }

    @Test
    void should_reject_an_unsupported_nested_value_as_a_monitoring_contract_defect() {
        assertThatThrownBy(() -> renderer.render(
                "request", new UnsupportedNestedPayload("not-a-record")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("project-owned records");
    }

    @Test
    void should_omit_the_sixty_fifth_rendered_field_and_mark_the_result_truncated() {
        ThirteenValues values = new ThirteenValues(
                "one", "two", "three", "four", "five", "six", "seven",
                "eight", "nine", "ten", "eleven", "twelve", "thirteen");

        RenderedApiMonitoring rendered = renderer.render("response", new SixtyFiveValues(
                values, values, values, values, values));

        String output = String.join("\n", rendered.segments());
        assertThat(output).contains("response.fifth.value12=twelve");
        assertThat(output).doesNotContain("response.fifth.value13=thirteen");
        assertThat(rendered.monitoringFieldsTruncated()).isTrue();
    }

    @Test
    void should_split_large_unicode_values_on_code_point_boundaries_with_bounded_utf8_segments() {
        String value = "prefix-" + "🙂".repeat(10_000);

        RenderedApiMonitoring rendered = renderer.render("request", new UnicodePayload(value));

        assertThat(rendered.segments()).hasSizeGreaterThan(1);
        for (String segment : rendered.segments()) {
            assertThat(segment.getBytes(StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(ApiMonitoringRenderer.MAX_SEGMENT_BYTES);
            assertThat(Character.isHighSurrogate(segment.charAt(segment.length() - 1))).isFalse();
            assertThat(Character.isLowSurrogate(segment.charAt(0))).isFalse();
        }
    }

    @Test
    void should_escape_control_characters_in_request_and_response_value_fields() {
        RenderedApiMonitoring request = renderer.render(
                "request", new ControlValuePayload("request\r\n\u0000\u0001"));
        RenderedApiMonitoring response = renderer.render(
                "response", new ControlValuePayload("response\r\n\u0000\u0001"));

        assertThat(String.join("", request.segments()))
                .contains("request.value=request\\u000D\\u000A\\u0000\\u0001")
                .doesNotContain("\r", "\n", "\u0000", "\u0001");
        assertThat(String.join("", response.segments()))
                .contains("response.value=response\\u000D\\u000A\\u0000\\u0001")
                .doesNotContain("\r", "\n", "\u0000", "\u0001");
        for (String segment : request.segments()) {
            assertThat(segment.getBytes(StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(ApiMonitoringRenderer.MAX_SEGMENT_BYTES);
        }
        for (String segment : response.segments()) {
            assertThat(segment.getBytes(StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(ApiMonitoringRenderer.MAX_SEGMENT_BYTES);
        }
    }

    @Test
    void should_allow_only_repository_relative_source_files() {
        RenderedApiMonitoring relative = renderer.render(
                "response", new SourceFilePayload("src/main/java/com/example/OrderService.java"));
        RenderedApiMonitoring slashAbsolute = renderer.render(
                "response", new SourceFilePayload("/srv/repos/orders/OrderService.java"));
        RenderedApiMonitoring uncAbsolute = renderer.render(
                "response", new SourceFilePayload("\\\\server\\orders\\OrderService.java"));
        RenderedApiMonitoring driveAbsolute = renderer.render(
                "response", new SourceFilePayload("C:\\repos\\orders\\OrderService.java"));

        assertThat(String.join("\n", relative.segments()))
                .contains("response.sourceFile=src/main/java/com/example/OrderService.java");
        assertThat(slashAbsolute.segments()).isEmpty();
        assertThat(uncAbsolute.segments()).isEmpty();
        assertThat(driveAbsolute.segments()).isEmpty();
    }

    @Test
    void should_omit_every_uri_scheme_from_source_file_and_preserve_business_identities() {
        RenderedApiMonitoring fileUri = renderer.render(
                "response", new SourceFilePayload("file:///srv/repos/orders/OrderService.java"));
        RenderedApiMonitoring jarUri = renderer.render(
                "response", new SourceFilePayload("jar:file:/opt/apps/orders.jar!/OrderService.class"));
        RenderedApiMonitoring unknownScheme = renderer.render(
                "response", new SourceFilePayload("s3:orders/OrderService.java"));
        RenderedApiMonitoring identities = renderer.render(
                "response", new BusinessIdentityPayload(
                        "s3:orders/events",
                        "mq:orders:created",
                        "module:OrderService"));

        assertThat(fileUri.segments()).isEmpty();
        assertThat(jarUri.segments()).isEmpty();
        assertThat(unknownScheme.segments()).isEmpty();
        assertThat(String.join("\n", identities.segments()))
                .contains("response.destination=s3:orders/events")
                .contains("response.identity=mq:orders:created")
                .contains("response.moduleIdentity=module:OrderService");
    }

    @Test
    void should_reject_unannotated_or_sensitive_value_components_as_monitoring_contract_defects() {
        assertThatThrownBy(() -> renderer.render("request", new UnannotatedPayload("repo-42")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ApiMonitoringField");
        assertThatThrownBy(() -> renderer.render("request", new UnsafePayload("select * from users")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OMIT");
    }

    private record RequestPayload(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String repositoryId,
            @ApiMonitoringField(ApiMonitoringMode.SIZE) List<String> labels,
            @ApiMonitoringField(ApiMonitoringMode.SIZE) String description,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) NestedPayload nested,
            @ApiMonitoringField(ApiMonitoringMode.OMIT) String source) {
    }

    private record NestedPayload(@ApiMonitoringField(ApiMonitoringMode.VALUE) String revision) {
    }

    private record NullableNestedPayload(
            @ApiMonitoringField(ApiMonitoringMode.NESTED) NestedPayload nested) {
    }

    private record NullableScalarPayload(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String identifier,
            @ApiMonitoringField(ApiMonitoringMode.SIZE) String description) {
    }

    private record OptionalNestedPayload(
            @ApiMonitoringField(ApiMonitoringMode.NESTED) Optional<NestedPayload> nested) {
    }

    private record OptionalScalarPayload(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) Optional<Integer> documentOrdinal,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) Optional<String> representation) {
    }

    private record UnsupportedNestedPayload(
            @ApiMonitoringField(ApiMonitoringMode.NESTED) Object nested) {
    }

    private record UnicodePayload(@ApiMonitoringField(ApiMonitoringMode.VALUE) String value) {
    }

    private record ControlValuePayload(@ApiMonitoringField(ApiMonitoringMode.VALUE) String value) {
    }

    private record SourceFilePayload(@ApiMonitoringField(ApiMonitoringMode.VALUE) String sourceFile) {
    }

    private record BusinessIdentityPayload(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String destination,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String identity,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String moduleIdentity) {
    }

    private record UnannotatedPayload(String repositoryId) {
    }

    private record UnsafePayload(@ApiMonitoringField(ApiMonitoringMode.VALUE) String sql) {
    }

    private record SixtyFiveValues(
            @ApiMonitoringField(ApiMonitoringMode.NESTED) ThirteenValues first,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) ThirteenValues second,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) ThirteenValues third,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) ThirteenValues fourth,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) ThirteenValues fifth) {
    }

    private record ThirteenValues(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String value1,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String value2,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String value3,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String value4,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String value5,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String value6,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String value7,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String value8,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String value9,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String value10,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String value11,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String value12,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String value13) {
    }
}
