package com.java.semantic.api;

import com.java.semantic.api.dto.BoundedResultResponse;
import com.java.semantic.api.dto.InternalSourceReferenceContextPayload;
import com.java.semantic.api.dto.InternalSourceReferenceRequest;
import com.java.semantic.api.dto.InternalSourceReferenceResponse;
import com.java.semantic.api.dto.InternalSourceReferenceResponse.ReferenceGroupResponse;
import com.java.semantic.api.dto.InternalSourceReferenceResponse.TargetDeclarationResponse;
import com.java.semantic.api.dto.InternalSourceReferenceTargetPayload;
import com.java.semantic.api.dto.JavaSourceSegmentRequest;
import com.java.semantic.api.dto.JavaSourceSegmentResponse;
import com.java.semantic.api.dto.PageResponse;
import com.java.semantic.api.dto.identity.JavaTypeIdentityPayload;
import com.java.semantic.api.dto.identity.MethodTargetPayload;
import com.java.semantic.api.dto.identity.SourceTypeIdentityPayload;
import com.java.semantic.api.dto.location.PositionPayload;
import com.java.semantic.api.dto.location.SourceRangePayload;
import com.java.semantic.api.dto.location.TextRangePayload;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;
import com.java.semantic.api.ApiMonitoringRenderer.RenderedApiMonitoring;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

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

    @Test
    void should_render_approved_internal_reference_and_source_segment_contract_fields() {
        SourceTypeIdentityPayload sourceType = new SourceTypeIdentityPayload(
                new JavaTypeIdentityPayload("com.secret.orders", "SensitiveOrderService"),
                "src/main/java/com/secret/orders/SensitiveOrderService.java");
        MethodTargetPayload method = new MethodTargetPayload(
                sourceType, "sensitiveMethod", List.of("com.secret.Order"));
        InternalSourceReferenceTargetPayload target = new InternalSourceReferenceTargetPayload.Method(
                "METHOD", method);
        TextRangePayload textRange = new TextRangePayload(
                new PositionPayload(7, 4), new PositionPayload(7, 19));
        SourceRangePayload sourceRange = new SourceRangePayload(sourceType.sourceFile(), textRange);
        InternalSourceReferenceRequest referenceRequest = new InternalSourceReferenceRequest(
                "orders", "a".repeat(40), target, 0, 20);
        InternalSourceReferenceResponse referenceResponse = new InternalSourceReferenceResponse(
                "orders",
                "a".repeat(40),
                "COMPLETE",
                new TargetDeclarationResponse(target, textRange, List.of()),
                3,
                List.of(),
                new PageResponse(0, 20, 0, 0, false),
                List.of(),
                List.of());
        ReferenceGroupResponse referenceGroup = new ReferenceGroupResponse(
                new InternalSourceReferenceContextPayload.Type("TYPE", sourceType),
                List.of(),
                new BoundedResultResponse(3, 0, 0, false),
                List.of(),
                List.of());
        JavaSourceSegmentRequest segmentRequest = new JavaSourceSegmentRequest(
                "orders", "a".repeat(40), sourceRange, 3);
        String sourceContent = String.join(
                " ",
                "private source content",
                "/srv/private",
                "file:///srv/private",
                "SEMANTIC_API_TOKEN=token",
                "JAVA_HOME=/opt/java",
                "JDT LS diagnostic");
        JavaSourceSegmentResponse segmentResponse = new JavaSourceSegmentResponse(
                "orders",
                "a".repeat(40),
                sourceRange,
                sourceContent,
                false,
                42);

        String output = List.of(
                        renderer.render("referenceRequest", referenceRequest),
                        renderer.render("referenceResponse", referenceResponse),
                        renderer.render("referenceGroup", referenceGroup),
                        renderer.render("segmentRequest", segmentRequest),
                        renderer.render("segmentResponse", segmentResponse))
                .stream()
                .flatMap(rendered -> rendered.segments().stream())
                .collect(Collectors.joining("\n"));

        assertThat(output)
                .contains("referenceRequest.repoId=orders")
                .contains("referenceRequest.target.kind=METHOD")
                .contains("referenceRequest.target.identity.sourceType.javaType.packageName=com.secret.orders")
                .contains("referenceRequest.target.identity.sourceType.javaType.className=SensitiveOrderService")
                .contains("referenceRequest.target.identity.sourceType.sourceFile="
                        + "src/main/java/com/secret/orders/SensitiveOrderService.java")
                .contains("referenceRequest.target.identity.methodName=sensitiveMethod")
                .contains("referenceRequest.target.identity.parameterTypes.size=1")
                .contains("referenceResponse.status=COMPLETE")
                .contains("referenceResponse.targetDeclaration.target.kind=METHOD")
                .contains("referenceResponse.targetDeclaration.declarationRange.start.line=7")
                .contains("referenceResponse.targetDeclaration.declarationRange.start.character=4")
                .contains("referenceResponse.targetDeclaration.declarationRange.end.line=7")
                .contains("referenceResponse.targetDeclaration.declarationRange.end.character=19")
                .contains("referenceResponse.totalReferenceCount=3")
                .contains("referenceResponse.page.limit=20")
                .contains("referenceGroup.context.kind=TYPE")
                .contains("referenceGroup.context.sourceType.javaType.packageName=com.secret.orders")
                .contains("referenceGroup.context.sourceType.javaType.className=SensitiveOrderService")
                .contains("referenceGroup.context.sourceType.sourceFile="
                        + "src/main/java/com/secret/orders/SensitiveOrderService.java")
                .contains("referenceGroup.limits.limit=3")
                .contains("segmentRequest.sourceRange.sourceFile="
                        + "src/main/java/com/secret/orders/SensitiveOrderService.java")
                .contains("segmentRequest.sourceRange.range.start.line=7")
                .contains("segmentRequest.sourceRange.range.end.character=19")
                .contains("segmentRequest.contextLines=3")
                .contains("segmentResponse.contentRange.sourceFile="
                        + "src/main/java/com/secret/orders/SensitiveOrderService.java")
                .contains("segmentResponse.contentRange.range.start.character=4")
                .contains("segmentResponse.contentRange.range.end.line=7")
                .contains("segmentResponse.contextTruncated=false")
                .contains("segmentResponse.returnedUtf8Bytes=42")
                .doesNotContain(
                        "private source content",
                        "/srv/private",
                        "file:///srv/private",
                        "SEMANTIC_API_TOKEN",
                        "JAVA_HOME",
                        "JDT LS diagnostic");
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
