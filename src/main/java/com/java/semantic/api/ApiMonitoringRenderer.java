package com.java.semantic.api;

import com.java.semantic.api.dto.DiscoverConceptsRequest;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 將明確允許的 API record 欄位轉為有界監控片段 */
public final class ApiMonitoringRenderer {

    public static final int MAX_RENDERED_FIELDS = 64;
    public static final int MAX_SEGMENT_BYTES = 32 * 1024;

    private static final String API_PACKAGE = "com.java.semantic.api";

    private static final Set<String> RESOURCE_URI_SCHEMES = Set.of(
            "bundle",
            "bundleresource",
            "classpath",
            "file",
            "ftp",
            "http",
            "https",
            "jar",
            "jrt",
            "resource",
            "vfs",
            "vfsfile",
            "vfszip",
            "wsjar",
            "zip");

    public RenderedApiMonitoring render(String root, Object value) {
        Assert.hasText(root, "root is required");
        Object recordValue = Objects.requireNonNull(value, "value is required");
        RenderState state = new RenderState();
        state.renderRecord(root, recordValue);
        return state.result();
    }

    public RenderedApiMonitoring render(ApiMonitoringContext context) {
        ApiMonitoringContext monitoringContext = Objects.requireNonNull(context, "context is required");
        RenderState state = new RenderState();
        monitoringContext.requestBody().ifPresent(value -> state.renderIfExplicitlyMonitored("request", value));
        monitoringContext.responseBody().ifPresent(value -> state.renderIfExplicitlyMonitored("response", value));
        return state.result();
    }

    private static boolean isProjectOwnedRecord(Object value) {
        Class<?> type = value.getClass();
        Package recordPackage = type.getPackage();
        String packageName = recordPackage.getName();
        return type.isRecord() && (API_PACKAGE.equals(packageName) || packageName.startsWith(API_PACKAGE + "."));
    }

    private static boolean isExplicitlyMonitoredRecord(Object value) {
        if (value instanceof DiscoverConceptsRequest) {
            return true;
        }
        if (!isProjectOwnedRecord(value)) {
            return false;
        }
        for (RecordComponent component : value.getClass().getRecordComponents()) {
            if (Objects.nonNull(component.getAnnotation(ApiMonitoringField.class))) {
                return true;
            }
        }
        return false;
    }

    /** 保存完成後可直接寫入日誌的安全片段 */
    public record RenderedApiMonitoring(List<String> segments, boolean monitoringFieldsTruncated) {

        public RenderedApiMonitoring {
            segments = List.copyOf(Objects.requireNonNull(segments, "segments is required"));
        }
    }

    private static final class RenderState {

        private final List<String> segments = new ArrayList<>();
        private final Set<Object> renderingRecords = Collections.newSetFromMap(new IdentityHashMap<>());
        private int renderedFieldCount;
        private boolean monitoringFieldsTruncated;

        void renderIfExplicitlyMonitored(String root, Object value) {
            if (value instanceof DiscoverConceptsRequest request) {
                renderDiscoverConceptsRequest(root, request);
                return;
            }
            if (isExplicitlyMonitoredRecord(value)) {
                renderRecord(root, value);
            }
        }

        void renderDiscoverConceptsRequest(String root, DiscoverConceptsRequest request) {
            renderRequestValue(root + ".repoId", "repoId", request.repoId());
            renderRequestValue(root + ".expectedRevision", "expectedRevision", request.expectedRevision());
            renderRequestSize(root + ".terms", request.terms());
            renderRequestSize(root + ".kinds", request.kinds());
            renderRequestValue(root + ".operator", "operator", request.operator());
            renderRequestValue(root + ".packagePrefix", "packagePrefix", request.packagePrefix());
            renderRequestValue(root + ".offset", "offset", request.offset());
            renderRequestValue(root + ".limit", "limit", request.limit());
        }

        void renderRequestValue(String fieldName, String propertyName, Object value) {
            if (Objects.isNull(value)) {
                return;
            }
            if (!isApprovedScalar(value)) {
                throw contractDefect("VALUE requires an approved scalar for " + propertyName);
            }
            String renderedValue = scalarValue(value);
            if (isDisallowedPath(propertyName, renderedValue)) {
                return;
            }
            if (requiresOmission(propertyName)) {
                throw contractDefect(propertyName + " must use OMIT");
            }
            appendField(fieldName, renderedValue);
        }

        void renderRequestSize(String fieldName, Collection<?> value) {
            if (Objects.nonNull(value)) {
                appendField(fieldName + ".size", Integer.toString(value.size()));
            }
        }

        void renderRecord(String root, Object value) {
            if (!isProjectOwnedRecord(value)) {
                throw contractDefect("monitoring accepts only project-owned records");
            }
            if (!renderingRecords.add(value)) {
                throw contractDefect("nested monitoring record must not contain a cycle");
            }
            try {
                for (RecordComponent component : value.getClass().getRecordComponents()) {
                    if (renderedFieldCount >= MAX_RENDERED_FIELDS) {
                        monitoringFieldsTruncated = true;
                        return;
                    }
                    renderComponent(root, value, component);
                }
            } finally {
                renderingRecords.remove(value);
            }
        }

        void renderComponent(String root, Object record, RecordComponent component) {
            ApiMonitoringField field = component.getAnnotation(ApiMonitoringField.class);
            if (Objects.isNull(field)) {
                throw contractDefect("every rendered record component requires ApiMonitoringField");
            }
            String fieldName = root + "." + component.getName();
            Object componentValue = componentValue(record, component);
            if (Objects.isNull(componentValue)) {
                return;
            }
            switch (field.value()) {
                case VALUE -> renderValue(fieldName, component, componentValue);
                case SIZE -> renderSize(fieldName, component, componentValue);
                case NESTED -> renderNested(fieldName, componentValue);
                case OMIT -> { }
            }
        }

        void renderValue(String fieldName, RecordComponent component, Object componentValue) {
            if (componentValue instanceof Optional<?> optionalValue) {
                optionalValue.ifPresent(value -> renderScalarValue(fieldName, component, value));
                return;
            }
            renderScalarValue(fieldName, component, componentValue);
        }

        private void renderScalarValue(String fieldName, RecordComponent component, Object componentValue) {
            if (!isApprovedScalar(componentValue)) {
                throw contractDefect("VALUE requires an approved scalar for " + component.getName());
            }
            String value = scalarValue(componentValue);
            if (isDisallowedPath(component.getName(), value)) {
                return;
            }
            if (requiresOmission(component.getName())) {
                throw contractDefect(component.getName() + " must use OMIT");
            }
            appendField(fieldName, value);
        }

        void renderSize(String fieldName, RecordComponent component, Object componentValue) {
            if (componentValue instanceof CharSequence sequence) {
                appendField(fieldName + ".size", Integer.toString(sequence.length()));
                return;
            }
            if (componentValue instanceof Collection<?> collection) {
                appendField(fieldName + ".size", Integer.toString(collection.size()));
                return;
            }
            throw contractDefect("SIZE requires a String or Collection for " + component.getName());
        }

        void renderNested(String fieldName, Object componentValue) {
            if (Objects.isNull(componentValue)) {
                return;
            }
            if (componentValue instanceof Optional<?> optionalValue) {
                optionalValue.ifPresent(value -> renderRecord(fieldName, value));
                return;
            }
            if (componentValue instanceof List<?> nestedRecords) {
                for (int index = 0; index < nestedRecords.size(); index++) {
                    renderRecord(fieldName + "[" + index + "]", nestedRecords.get(index));
                }
                return;
            }
            renderRecord(fieldName, componentValue);
        }

        void appendField(String fieldName, String value) {
            if (renderedFieldCount >= MAX_RENDERED_FIELDS) {
                monitoringFieldsTruncated = true;
                return;
            }
            renderedFieldCount++;
            appendBoundedSegments(fieldName, safeLogValue(value));
        }

        private String safeLogValue(String value) {
            StringBuilder safeValue = new StringBuilder();
            for (int index = 0; index < value.length();) {
                int codePoint = value.codePointAt(index);
                if (Character.isISOControl(codePoint)) {
                    safeValue.append(String.format(Locale.ROOT, "\\u%04X", codePoint));
                } else {
                    safeValue.appendCodePoint(codePoint);
                }
                index += Character.charCount(codePoint);
            }
            return safeValue.toString();
        }

        void appendBoundedSegments(String fieldName, String value) {
            int valueIndex = 0;
            int segmentNumber = 1;
            do {
                String suffix = segmentNumber == 1 ? "" : "#" + segmentNumber;
                String prefix = fieldName + suffix + "=";
                int prefixBytes = prefix.getBytes(StandardCharsets.UTF_8).length;
                if (prefixBytes >= MAX_SEGMENT_BYTES) {
                    throw contractDefect("monitoring field name exceeds the segment limit");
                }
                int nextValueIndex = nextSegmentBoundary(value, valueIndex, MAX_SEGMENT_BYTES - prefixBytes);
                segments.add(prefix + value.substring(valueIndex, nextValueIndex));
                valueIndex = nextValueIndex;
                segmentNumber++;
            } while (valueIndex < value.length());
        }

        int nextSegmentBoundary(String value, int startIndex, int availableBytes) {
            int index = startIndex;
            int consumedBytes = 0;
            while (index < value.length()) {
                int codePoint = value.codePointAt(index);
                int codePointBytes = utf8Bytes(codePoint);
                if (consumedBytes + codePointBytes > availableBytes) {
                    break;
                }
                consumedBytes += codePointBytes;
                index += Character.charCount(codePoint);
            }
            if (index == startIndex && startIndex < value.length()) {
                throw contractDefect("monitoring value cannot fit into a segment");
            }
            return index;
        }

        RenderedApiMonitoring result() {
            return new RenderedApiMonitoring(segments, monitoringFieldsTruncated);
        }

        private Object componentValue(Object record, RecordComponent component) {
            try {
                return component.getAccessor().invoke(record);
            } catch (IllegalAccessException | InvocationTargetException exception) {
                throw contractDefect("unable to read monitored record component", exception);
            }
        }

        private boolean isApprovedScalar(Object value) {
            return value instanceof String
                    || value instanceof Number
                    || value instanceof Boolean
                    || value instanceof Character
                    || value instanceof Enum<?>
                    || value instanceof UUID;
        }

        private String scalarValue(Object value) {
            if (value instanceof String string) {
                return string;
            }
            if (value instanceof Enum<?> enumeration) {
                return enumeration.name();
            }
            if (value instanceof UUID identifier) {
                return identifier.toString();
            }
            if (value instanceof Number number) {
                return number.toString();
            }
            if (value instanceof Boolean booleanValue) {
                return booleanValue.toString();
            }
            if (value instanceof Character character) {
                return character.toString();
            }
            throw contractDefect("VALUE requires an approved scalar");
        }

        private boolean isDisallowedPath(String componentName, String value) {
            if (isAbsoluteFilesystemPath(value)) {
                return true;
            }
            if ("sourceFile".equals(componentName)) {
                return hasUriSchemePrefix(value);
            }
            return isKnownResourceUri(value);
        }

        private boolean isAbsoluteFilesystemPath(String value) {
            return value.startsWith("/")
                    || value.startsWith("\\\\")
                    || value.matches("^[A-Za-z]:[\\\\/].*");
        }

        private boolean hasUriSchemePrefix(String value) {
            int schemeSeparator = value.indexOf(':');
            return schemeSeparator > 0 && isUriScheme(value.substring(0, schemeSeparator));
        }

        private boolean isKnownResourceUri(String value) {
            int schemeSeparator = value.indexOf(':');
            if (!hasUriSchemePrefix(value)) {
                return false;
            }
            String scheme = value.substring(0, schemeSeparator).toLowerCase(Locale.ROOT);
            String schemeSpecificPart = value.substring(schemeSeparator + 1);
            return RESOURCE_URI_SCHEMES.contains(scheme)
                    || schemeSpecificPart.startsWith("/")
                    || schemeSpecificPart.startsWith("\\")
                    || hasNestedResourceUri(schemeSpecificPart);
        }

        private boolean hasNestedResourceUri(String value) {
            int schemeSeparator = value.indexOf(':');
            if (schemeSeparator <= 0 || !isUriScheme(value.substring(0, schemeSeparator))) {
                return false;
            }
            String scheme = value.substring(0, schemeSeparator).toLowerCase(Locale.ROOT);
            String schemeSpecificPart = value.substring(schemeSeparator + 1);
            return RESOURCE_URI_SCHEMES.contains(scheme)
                    || schemeSpecificPart.startsWith("/")
                    || schemeSpecificPart.startsWith("\\");
        }

        private boolean isUriScheme(String value) {
            if (value.isEmpty() || !Character.isLetter(value.charAt(0))) {
                return false;
            }
            return value.codePoints().skip(1).allMatch(codePoint -> Character.isLetterOrDigit(codePoint)
                    || codePoint == '+'
                    || codePoint == '-'
                    || codePoint == '.');
        }

        private boolean requiresOmission(String componentName) {
            if ("sourceFile".equals(componentName)) {
                return false;
            }
            String normalizedName = componentName.toLowerCase(Locale.ROOT);
            return normalizedName.contains("source")
                    || normalizedName.contains("sql")
                    || normalizedName.contains("xml")
                    || normalizedName.contains("content")
                    || normalizedName.contains("authorization")
                    || normalizedName.contains("token")
                    || normalizedName.contains("credential")
                    || normalizedName.contains("password")
                    || normalizedName.contains("secret");
        }

        private IllegalStateException contractDefect(String message) {
            return new IllegalStateException("API monitoring contract defect: " + message);
        }

        private IllegalStateException contractDefect(String message, Exception cause) {
            return new IllegalStateException("API monitoring contract defect: " + message, cause);
        }

        private int utf8Bytes(int codePoint) {
            if (codePoint <= 0x7f) {
                return 1;
            }
            if (codePoint <= 0x7ff) {
                return 2;
            }
            if (codePoint <= 0xffff) {
                return 3;
            }
            return 4;
        }
    }
}
